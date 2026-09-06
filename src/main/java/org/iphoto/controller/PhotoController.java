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
import org.apache.commons.io.FilenameUtils;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.PhotoService;
import org.iphoto.service.SpaceService;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    // ISO_LOCAL_DATE_TIME (not a fixed minute-only pattern) so this stays backward compatible with
    // the datetime-local picker's "yyyy-MM-ddTHH:mm" strings (seconds/nanos are optional in this
    // formatter) while also accepting the second/millisecond-precision strings the drag-to-reorder
    // feature needs (PhotoGrid.jsx's drag handle, via usePhotoActions.js#handleReorderPhotos) -
    // that feature stamps moved photos exactly 1 second (or, in a tight gap, 1 millisecond) apart
    // to preserve their new relative order, which a minute-only formatter couldn't represent at all.
    private final DateTimeFormatter df = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    @GetMapping
    public PageResponse<Photo> getPhotos(@PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(currentUser);
        return PageResponse.from(photoService.getPhotos(space.getId(), pageable));
    }

    // Day-index browsing (replaces the old timeline/jumpToMonth-cursor machinery): every day
    // that has a photo, with its count and a cover - fetched once up front so the client can
    // render every day section immediately and only lazily fetch each day's actual photos
    // (getPhotosForDay below) as its section scrolls into view. Main Space only, same scope as
    // GET /api/photos.
    @GetMapping("/days")
    public List<DayBucketDTO> getDays() {
        UserEntity currentUser = getCurrentUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(currentUser);
        return photoService.getDailyPhotoCounts(space.getId());
    }

    @GetMapping("/day/{day}")
    public PageResponse<Photo> getPhotosForDay(@PathVariable String day,
                                                @PageableDefault(size = 100, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(currentUser);
        return photoService.getPhotosForDay(space.getId(), LocalDate.parse(day), pageable);
    }

    @GetMapping("/space/{spaceId}")
    public PageResponse<Photo> getPhotos(@PathVariable UUID spaceId, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return spaceService.getSpaceByIdForUser(spaceId, currentUser)
                .map(space -> PageResponse.from(photoService.getPhotos(space.id(), pageable)))
                .orElseGet(() -> PageResponse.from(Page.empty()));
    }

    // Library's "N months/years ago" bar - see PhotoService.getMemories/IPhotoProperties.Memories.
    @GetMapping("/memories")
    public List<MemoryDTO> getMemories() {
        UserEntity currentUser = getCurrentUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(currentUser);
        return photoService.getMemories(space.getId());
    }

    @GetMapping("/space/{spaceId}/days")
    public List<DayBucketDTO> getSpaceDays(@PathVariable UUID spaceId) {
        UserEntity currentUser = getCurrentUser();
        return spaceService.getSpaceByIdForUser(spaceId, currentUser)
                .map(space -> photoService.getDailyPhotoCounts(space.id()))
                .orElseGet(List::of);
    }

    @GetMapping("/space/{spaceId}/day/{day}")
    public PageResponse<Photo> getSpacePhotosForDay(@PathVariable UUID spaceId, @PathVariable String day,
                                                     @PageableDefault(size = 100, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return spaceService.getSpaceByIdForUser(spaceId, currentUser)
                .map(space -> photoService.getPhotosForDay(space.id(), LocalDate.parse(day), pageable))
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

    // Garbage's day-index browsing (replaces the old flat GET /api/photos/garbage) - cross-space,
    // same scope the old endpoint had.
    @GetMapping("/garbage/days")
    public List<DayBucketDTO> getGarbageDays() {
        UserEntity currentUser = getCurrentUser();
        return photoService.getGarbageDailyPhotoCounts(currentUser);
    }

    @GetMapping("/garbage/day/{day}")
    public PageResponse<Photo> getGarbagePhotosForDay(@PathVariable String day,
                                                       @PageableDefault(size = 100, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return PageResponse.from(photoService.getGarbagePhotosForDay(currentUser, LocalDate.parse(day), pageable));
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

    // Deliberately a separate route from /original above, not that same endpoint with a header
    // added - /original is used as an <img src="..."> for in-page rendering all over the frontend
    // (Lightbox full-res view, UploadPreviewModal, DuplicateClusterModal, SharedLightbox), and a
    // browser won't render an image inline from a response marked Content-Disposition: attachment.
    // Single-photo counterpart of downloadMultiple below.
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        return photoService.findById(id)
                .filter(photo -> photo.getSpace().getUser().getId().equals(currentUser.getId()))
                .map(this::serveAsAttachment)
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<Resource> serveAsAttachment(PhotoEntity photo) {
        String filePath = photo.getOriginalPath();
        if (filePath == null) return ResponseEntity.notFound().build();
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(path);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + attachmentFileName(photo) + "\"");
        try {
            return response.contentType(MediaType.parseMediaType(photo.getContentType())).body(resource);
        } catch (Exception e) {
            return response.contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);
        }
    }

    // Multiple selected photos download as one zip instead of one file at a time - POST with a
    // body (not GET+query-string) since a large selection could exceed a comfortable URL length.
    // findOwnedByIds is the authorization boundary here: silently drops any id the caller doesn't
    // actually own rather than serving it, so a client can't read another user's files by passing
    // arbitrary ids. Streams via StreamingResponseBody wrapped around a plain ZipOutputStream,
    // writing straight to the response - never buffers the whole zip in memory, since a large
    // selection could be many GB.
    @PostMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadMultiple(@RequestBody List<UUID> photoIds) {
        UserEntity currentUser = getCurrentUser();
        List<PhotoEntity> photos = photoService.findOwnedByIds(currentUser, photoIds);
        if (photos.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody body = outputStream -> {
            Set<String> usedNames = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
                for (PhotoEntity photo : photos) {
                    if (photo.getOriginalPath() == null) continue;
                    Path path = Paths.get(photo.getOriginalPath());
                    if (!Files.exists(path)) continue;
                    zip.putNextEntry(new ZipEntry(uniqueEntryName(photo, usedNames)));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"photos.zip\"")
                .contentType(MediaType.valueOf("application/zip"))
                .body(body);
    }

    private static String attachmentFileName(PhotoEntity photo) {
        return StringUtils.hasText(photo.getFileName()) ? photo.getFileName() : photo.getId() + ".bin";
    }

    // De-duplicates zip entry names within one download - two selected photos can legitimately
    // share a filename (duplicates across spaces/albums, or two differently-cropped copies of the
    // same original), which would otherwise silently overwrite one entry with the other in the
    // resulting zip.
    private static String uniqueEntryName(PhotoEntity photo, Set<String> usedNames) {
        String fileName = attachmentFileName(photo);
        if (usedNames.add(fileName)) {
            return fileName;
        }
        String base = FilenameUtils.getBaseName(fileName);
        String extension = FilenameUtils.getExtension(fileName);
        String suffix = photo.getId().toString().substring(0, 8);
        String deduped = StringUtils.hasText(extension) ? base + "-" + suffix + "." + extension : base + "-" + suffix;
        usedNames.add(deduped);
        return deduped;
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