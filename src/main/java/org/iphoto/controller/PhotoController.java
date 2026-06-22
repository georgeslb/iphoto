package org.iphoto.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.mappers.PhotoMetadataMapper;
import org.iphoto.model.*;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.PhotoService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final PhotoRepository photoRepository; // Keep for direct access where appropriate, but prefer service
    private final PhotoMetadataRepository metadataRepository;
    private final PhotoMapper photoMapper;
    private final PhotoMetadataMapper photoMetadataMapper;
    private final UserRepository userRepository; // To fetch UserEntity from username
    private final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    @GetMapping
    public PageResponse<Photo> getPhotos(@PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return PageResponse.from(photoService.getPhotos(currentUser, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Photo> getPhoto(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        return photoService.findById(currentUser, id)
                .map(photoMapper::photoEntityToPhoto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/date")
    public ResponseEntity<Photo> updatePhotoDate(@PathVariable UUID id, @RequestBody UpdatePhotoDateRequest request) {
        UserEntity currentUser = getCurrentUser();
        try {
            Photo photo = photoService.updatePhotoDate(currentUser, id, LocalDateTime.parse(request.newDate(), df));
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

    @PostMapping("/garbage")
    public ResponseEntity<Void> moveAssetsToGarbage(@RequestBody List<UUID> ids) throws Exception {
        UserEntity currentUser = getCurrentUser();
        photoService.moveAssetsToGarbage(currentUser, ids);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/albums")
    public ResponseEntity<List<AlbumDTO>> getAlbumByPhoto(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        try {
            List<AlbumDTO> albumDTOS = photoService.getAlbumsByPhoto(currentUser, id);
            return ResponseEntity.ok(albumDTOS);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/garbage/restore")
    public ResponseEntity<Void> restoreAssets(@RequestBody List<UUID> ids) {
        UserEntity currentUser = getCurrentUser();
        photoService.restoreAssetsFromGarbage(currentUser, ids);
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
                .filter(metadata -> metadata.getPhoto().getUser().equals(currentUser))
                .map(photoMetadataMapper::photoMetadataEntityToPhotoMetadata)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/locations")
    public List<LocationDTO> getLocations() {
        UserEntity currentUser = getCurrentUser();
        return metadataRepository.findAll().stream()
                .filter(m -> m.getPhoto().getUser().equals(currentUser) && m.getLatitude() != null && !m.getPhoto().getIsGarbage())
                .map(m -> new LocationDTO(m.getId(), m.getLatitude(), m.getLongitude(), m.getPhoto().getFileName(), "/api/photos/" + m.getId() + "/thumbnail"))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> getThumbnail(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        return photoService.findById(currentUser, id).map(photo -> this.serveFile(photo, true)).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<Resource> getOriginal(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        return photoService.findById(currentUser, id).map(photo -> this.serveFile(photo, false)).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/stream")
    public ResponseEntity<ResourceRegion> streamVideo(@PathVariable UUID id, @RequestHeader HttpHeaders headers) throws IOException {
        UserEntity currentUser = getCurrentUser();
        PhotoEntity photo = photoService.findById(currentUser, id).orElse(null);
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