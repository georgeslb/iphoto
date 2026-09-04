package org.iphoto.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.mappers.PhotoMetadataMapper;
import org.iphoto.model.*;
import org.iphoto.model.request.CheckHashesRequest;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.model.request.PhotoSelectionRequest;
import org.iphoto.model.request.UpdatePhotoDateRequest;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.PhotoService;
import org.iphoto.service.SpaceService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/photos")
@AllArgsConstructor
@Slf4j
public class PhotoController {

    private final PhotoService photoService;
    private final PhotoMetadataRepository metadataRepository;
    private final PhotoMapper photoMapper;
    private final PhotoMetadataMapper photoMetadataMapper;
    private final UserRepository userRepository; // To fetch UserEntity from username
    private final SpaceService spaceService;
    private final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    // `jumpToMonth`/`beforeDate`/`afterDate` are all optional cursor params for the Library's
    // bidirectional scroll (LibraryTimeline.jsx / usePhotoData.js) layered on top of the plain
    // `page`-based feed everything else here still uses by default:
    //   - `jumpToMonth=yyyy-MM`: the initial jump (Memories card / browse-by-month click) — landing
    //     the feed at the start of that month.
    //   - `beforeDate`=<a photo's own `fullDateTime`, verbatim>: continued OLDER-direction paging
    //     once already jumped — an exact cursor instead of `jumpToMonth`'s month granularity, so
    //     repeated "load more" calls don't refetch/skip anything at the month boundary.
    //   - `afterDate`=<a photo's own `fullDateTime`, verbatim>: NEWER-direction paging — scrolling
    //     back up above a jumped-to point. Takes priority over the other two if somehow both are
    //     present; a request only ever needs one direction.
    // `beforeDate` wins over `jumpToMonth` if both are somehow present (the frontend only ever
    // sends one or the other: `jumpToMonth` for the first jump, `beforeDate` for continuing it).
    @GetMapping
    public PageResponse<Photo> getPhotos(@PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable,
                                          @RequestParam(required = false) String jumpToMonth,
                                          @RequestParam(required = false) String beforeDate,
                                          @RequestParam(required = false) String afterDate) {
        UserEntity currentUser = getCurrentUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(currentUser);

        if (StringUtils.hasText(afterDate)) {
            LocalDateTime cutoff = parseCursor(afterDate);
            if (cutoff == null) {
                return PageResponse.from(photoService.getPhotos(space.getId(), pageable));
            }
            Pageable ascending = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("dateTaken").ascending());
            return PageResponse.from(photoService.getPhotosAfter(space.getId(), ascending, cutoff));
        }

        LocalDateTime cutoff = StringUtils.hasText(beforeDate) ? parseCursor(beforeDate) : parseJumpToMonth(jumpToMonth);
        return PageResponse.from(photoService.getPhotos(space.getId(), pageable, cutoff));
    }

    // "yyyy-MM" (e.g. "2026-03", what a Memories card / browse-by-month click sends for the
    // INITIAL jump) -> the first instant of the FOLLOWING month, so filtering dateTaken < that
    // cutoff lands the feed exactly at the start of the requested month.
    private LocalDateTime parseJumpToMonth(String jumpToMonth) {
        if (!StringUtils.hasText(jumpToMonth)) {
            return null;
        }
        try {
            return YearMonth.parse(jumpToMonth).plusMonths(1).atDay(1).atStartOfDay();
        } catch (DateTimeParseException e) {
            log.warn("ignoring malformed jumpToMonth param: {}", jumpToMonth);
            return null;
        }
    }

    // A photo's own `fullDateTime` (ISO_LOCAL_DATE_TIME, see PhotoMapper) sent straight back as an
    // exact pagination cursor for `beforeDate`/`afterDate`. Malformed or absent input is treated as
    // "no cursor" rather than a 400 — same reasoning as parseJumpToMonth: this is an optional UX
    // param on an otherwise normal listing endpoint.
    private LocalDateTime parseCursor(String isoDateTime) {
        try {
            return LocalDateTime.parse(isoDateTime);
        } catch (DateTimeParseException e) {
            log.warn("ignoring malformed date cursor param: {}", isoDateTime);
            return null;
        }
    }

    // The Library "month/year index" backing Memories and the browse-by-month list (see
    // TimelineMonthDTO / PhotoService.getTimeline) — Main Space only, same scope as GET /api/photos.
    @GetMapping("/timeline")
    public List<TimelineMonthDTO> getTimeline() {
        UserEntity currentUser = getCurrentUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(currentUser);
        return photoService.getTimeline(space.getId());
    }

    @GetMapping("/space/{spaceId}")
    public PageResponse<Photo> getPhotos(@PathVariable UUID spaceId, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return spaceService.getSpaceByIdForUser(spaceId, currentUser)
                .map(space -> PageResponse.from(photoService.getPhotos(space.id(), pageable)))
                .orElseGet(() -> PageResponse.from(Page.empty()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Photo> getPhoto(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        return photoService.findById(id)
                .filter(photoEntity -> photoEntity.getSpace().getUser().getId().equals(currentUser.getId()))
                .map(photoMapper::photoEntityToPhoto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/date")
    public ResponseEntity<Photo> updatePhotoDate(@PathVariable UUID id, @RequestBody UpdatePhotoDateRequest request) {
        try {
            Photo photo = photoService.updatePhotoDate(id, LocalDateTime.parse(request.newDate(), df));
            return ResponseEntity.ok(photo);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping(value = "/{id}/edit", consumes = "multipart/form-data")
    public ResponseEntity<Photo> editPhoto(@PathVariable UUID id,
                                           @RequestPart("file") MultipartFile file,
                                           @ModelAttribute PhotoEditRequest request) {
        UserEntity currentUser = getCurrentUser();
        try {
            Photo photo = photoService.editPhoto(currentUser, id, file, request);
            return ResponseEntity.ok(photo);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/garbage")
    public PageResponse<Photo> getGarbage(@PageableDefault(size = 50, sort = "garbageAt", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return PageResponse.from(photoService.getGarbagePhotos(currentUser, pageable));
    }

    @PostMapping("/selected")
    public PhotoSelectionDTO getSelection(@RequestBody PhotoSelectionRequest photoSelectionRequest) {
        UserEntity currentUser = getCurrentUser();
        return photoService.getSelection(photoSelectionRequest, currentUser);
    }

    // Mobile camera-sync: "which of these do you already have" before spending bandwidth
    // uploading them. See PhotoRepository.findExistingFileHashes for why this is a deliberately
    // global (not per-user) lookup, and why the response only ever echoes back hashes.
    @PostMapping("/check-hashes")
    public CheckHashesResponse checkHashes(@RequestBody CheckHashesRequest request) {
        return new CheckHashesResponse(photoService.checkExistingHashes(request.hashes()));
    }

    @PostMapping("/garbage")
    public ResponseEntity<Void> moveAssetsToGarbage(@RequestBody List<UUID> ids) throws Exception {
        UserEntity currentUser = getCurrentUser();
        photoService.moveAssetsToGarbage(currentUser, ids);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/main")
    public ResponseEntity<Void> moveAssetsToMain(@RequestBody List<UUID> ids) throws Exception {
        UserEntity user = getCurrentUser();
        photoService.moveAssetsToMain(user, ids);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/albums")
    public ResponseEntity<List<AlbumDTO>> getAlbumByPhoto(@PathVariable UUID id) {
        try {
            List<AlbumDTO> albumDTOS = photoService.getAlbumsByPhoto(id);
            return ResponseEntity.ok(albumDTOS);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/{id}/space")
    public ResponseEntity<SpaceDTO> getPhotoSpace(@PathVariable UUID id) {
        try {
            UserEntity currentUser = getCurrentUser();
            return ResponseEntity.ok(spaceService.getPhotoSpace(currentUser, id));
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/garbage/restore")
    public ResponseEntity<Void> restoreAssets(@RequestBody List<UUID> ids) throws Exception {
        UserEntity currentUser = getCurrentUser();
        photoService.restoreAssetsFromGarbage(currentUser,ids);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/garbage/permanent")
    public ResponseEntity<Void> permanentDeleteAssets(@RequestBody List<UUID> ids) {
        UserEntity currentUser = getCurrentUser();
        photoService.permanentDeleteAssets(currentUser, ids);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/metadata")
    public ResponseEntity<PhotoMetadata> getMetadata(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        // Ensure the photo associated with the metadata belongs to the user
        return metadataRepository.findById(id)
                .filter(metadata -> metadata.getPhoto().getSpace().getUser().equals(currentUser))
                .map(photoMetadataMapper::photoMetadataEntityToPhotoMetadata)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/locations")
    public List<LocationDTO> getLocations() {
        UserEntity currentUser = getCurrentUser();
        return metadataRepository.findAll().stream()
                .filter(m -> m.getPhoto().getSpace().getUser().equals(currentUser) && m.getLatitude() != null && !m.getPhoto().getIsGarbage())
                .map(m -> new LocationDTO(m.getId(), m.getLatitude(), m.getLongitude(), m.getPhoto().getFileName(), "/api/photos/" + m.getId() + "/thumbnail"))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> getThumbnail(@PathVariable UUID id) {
        return photoService.findById(id).map(photo -> this.serveFile(photo, true)).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<Resource> getOriginal(@PathVariable UUID id) {
        return photoService.findById(id).map(photo -> this.serveFile(photo, false)).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/stream")
    public ResponseEntity<ResourceRegion> streamVideo(@PathVariable UUID id, @RequestHeader HttpHeaders headers) throws IOException {
        PhotoEntity photo = photoService.findById(id).orElse(null);
        if (photo == null || photo.getOriginalPath() == null) return ResponseEntity.notFound().build();
        Path path = Paths.get(photo.getOriginalPath());
        Resource video = new FileSystemResource(path);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).contentType(MediaType.parseMediaType(photo.getContentType())).body(resourceRegion(video, headers));
    }

    private ResourceRegion resourceRegion(Resource video, HttpHeaders headers) throws IOException {
        long len = video.contentLength();
        HttpRange range = headers.getRange().isEmpty() ? null : headers.getRange().getFirst();
        if (range != null) {
            long start = range.getRangeStart(len);
            long end = range.getRangeEnd(len);
            return new ResourceRegion(video, start, Math.min(1024 * 1024L, end - start + 1));
        }
        return new ResourceRegion(video, 0, Math.min(1024 * 1024L, len));
    }

    private ResponseEntity<Resource> serveFile(PhotoEntity photo, boolean isThumbnail) {
        String filePath = photo.getOriginalPath();
        if (isThumbnail) {
           filePath = photo.getThumbnailPath();
        }
        if (filePath == null) return ResponseEntity.notFound().build();
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(path);
        try {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.getContentType())).body(resource);
        } catch (Exception e) {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(resource);

        }
    }
}