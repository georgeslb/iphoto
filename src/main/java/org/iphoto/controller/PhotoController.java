package org.iphoto.controller;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.PhotoMetadataEntity;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/photos")
public class PhotoController {

    private final PhotoService photoService;
    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;

    public PhotoController(PhotoService photoService, PhotoRepository photoRepository, PhotoMetadataRepository metadataRepository) {
        this.photoService = photoService;
        this.photoRepository = photoRepository;
        this.metadataRepository = metadataRepository;
    }

    @GetMapping
    public PageResponse<Photo> getPhotos(@PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(photoService.getPhotos(pageable));
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

    @PostMapping("/garbage/restore")
    public ResponseEntity<Void> restoreAssets(@RequestBody List<UUID> ids) {
        photoService.restoreAssetsFromGarbage(ids);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/garbage/permanent")
    public ResponseEntity<Void> permanentDeleteAssets(@RequestBody List<UUID> ids) throws IOException {
        photoService.permanentDeleteAssets(ids);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/metadata")
    public ResponseEntity<PhotoMetadataEntity> getMetadata(@PathVariable UUID id) {
        return metadataRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
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
        return photoRepository.findById(id).map(PhotoEntity::getThumbnailPath).map(this::serveFile).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<Resource> getOriginal(@PathVariable UUID id) {
        return photoRepository.findById(id).map(PhotoEntity::getOriginalPath).map(this::serveFile).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/stream")
    public ResponseEntity<ResourceRegion> streamVideo(@PathVariable UUID id, @RequestHeader HttpHeaders headers) throws IOException {
        PhotoEntity entity = photoRepository.findById(id).orElse(null);
        if (entity == null || entity.getOriginalPath() == null) return ResponseEntity.notFound().build();
        Path path = Paths.get(entity.getOriginalPath());
        Resource video = new FileSystemResource(path);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).contentType(MediaType.parseMediaType(Files.probeContentType(path))).body(resourceRegion(video, headers));
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

    private ResponseEntity<Resource> serveFile(String filePath) {
        if (filePath == null) return ResponseEntity.notFound().build();
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(path);
        try { return ResponseEntity.ok().contentType(MediaType.parseMediaType(Files.probeContentType(path))).body(resource); } catch (IOException e) { return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(resource); }
    }
}
