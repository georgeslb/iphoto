package org.iphoto.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.mappers.PhotoMetadataMapper;
import org.iphoto.model.*;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.service.PhotoService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
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
    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;
    private final PhotoMapper photoMapper;
    private final PhotoMetadataMapper photoMetadataMapper;
    private final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @GetMapping
    public PageResponse<Photo> getPhotos(@PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(photoService.getPhotos(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Photo> getPhoto(@PathVariable UUID id) {
        return photoRepository.findById(id)
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
        try {
            Photo photo = photoService.editPhoto(id, file, request);
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
        return PageResponse.from(photoService.getGarbagePhotos(pageable));
    }

    @PostMapping("/garbage")
    public ResponseEntity<Void> moveAssetsToGarbage(@RequestBody List<UUID> ids) throws Exception {
        photoService.moveAssetsToGarbage(ids);
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

    @PostMapping("/garbage/restore")
    public ResponseEntity<Void> restoreAssets(@RequestBody List<UUID> ids) {
        photoService.restoreAssetsFromGarbage(ids);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/garbage/permanent")
    public ResponseEntity<Void> permanentDeleteAssets(@RequestBody List<UUID> ids) {
        photoService.permanentDeleteAssets(ids);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/metadata")
    public ResponseEntity<PhotoMetadata> getMetadata(@PathVariable UUID id) {
        return metadataRepository.findById(id).map(photoMetadataMapper::photoMetadataEntityToPhotoMetadata).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/locations")
    public List<LocationDTO> getLocations() {
        return metadataRepository.findAll().stream()
                .filter(m -> m.getLatitude() != null && m.getPhoto().getIsGarbage() == false)
                .map(m -> new LocationDTO(m.getId(), m.getLatitude(), m.getLongitude(), m.getPhoto().getFileName(), "/api/photos/" + m.getId() + "/thumbnail"))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> getThumbnail(@PathVariable UUID id) {
        return photoRepository.findById(id).map(photo -> this.serveFile(photo, true)).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<Resource> getOriginal(@PathVariable UUID id) {
        return photoRepository.findById(id).map(photo -> this.serveFile(photo, false)).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/stream")
    public ResponseEntity<ResourceRegion> streamVideo(@PathVariable UUID id, @RequestHeader HttpHeaders headers) throws IOException {
        PhotoEntity photo = photoRepository.findById(id).orElse(null);
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
