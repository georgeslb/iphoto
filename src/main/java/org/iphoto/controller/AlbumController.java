package org.iphoto.controller;

import org.iphoto.model.AlbumCreateRequest;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.AlbumPhotoRequest;
import org.iphoto.model.AlbumUpdateRequest;
import org.iphoto.model.Photo;
import org.iphoto.service.AlbumService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/albums")
public class AlbumController {

    private final AlbumService albumService;

    public AlbumController(AlbumService albumService) {
        this.albumService = albumService;
    }

    @PostMapping
    public ResponseEntity<AlbumDTO> createAlbum(@RequestBody AlbumCreateRequest request) {
        try {
            AlbumDTO album = albumService.createAlbum(request);
            return new ResponseEntity<>(album, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.CONFLICT); // Album with name already exists
        }
    }

    @GetMapping
    public ResponseEntity<List<AlbumDTO>> getAllAlbums() {
        List<AlbumDTO> albums = albumService.getAllAlbums();
        return ResponseEntity.ok(albums);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlbumDTO> getAlbumById(@PathVariable UUID id) {
        try {
            AlbumDTO album = albumService.getAlbumById(id);
            return ResponseEntity.ok(album);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<AlbumDTO> updateAlbum(@PathVariable UUID id, @RequestBody AlbumUpdateRequest request) {
        try {
            AlbumDTO album = albumService.updateAlbum(id, request);
            return ResponseEntity.ok(album);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.CONFLICT);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAlbum(@PathVariable UUID id) {
        albumService.deleteAlbum(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @PostMapping("/{id}/photos")
    public ResponseEntity<AlbumDTO> addPhotosToAlbum(@PathVariable UUID id, @RequestBody AlbumPhotoRequest request) {
        try {
            AlbumDTO album = albumService.addPhotosToAlbum(id, request.photoIds());
            return ResponseEntity.ok(album);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @DeleteMapping("/{id}/photos")
    public ResponseEntity<AlbumDTO> removePhotosFromAlbum(@PathVariable UUID id, @RequestBody AlbumPhotoRequest request) {
        try {
            AlbumDTO album = albumService.removePhotosFromAlbum(id, request.photoIds());
            return ResponseEntity.ok(album);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/{id}/photos")
    public ResponseEntity<List<Photo>> getAlbumPhotos(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        try {
            List<Photo> photos = albumService.getAlbumPhotos(id, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}
