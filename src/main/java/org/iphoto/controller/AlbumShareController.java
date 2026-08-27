package org.iphoto.controller;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.Photo;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.AlbumService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/albums/share")
@Slf4j
public class AlbumShareController extends AbstractBaseController {

    private final AlbumService albumService;

    public AlbumShareController(AlbumService albumService, UserRepository userRepository) {
        super(userRepository);
        this.albumService = albumService;
    }



    @GetMapping("/{id}/photos")
    public ResponseEntity<Page<Photo>> getAlbumPhotos(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        try {
            UserEntity currentUser = getCurrentUser();
        } catch (NoSuchElementException e) {
            log.warn("anonymous user fetching shared album");
        }

        try {
            Page<Photo> photos = albumService.getAlbumPhotosForShare(id, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    // Backs the shared Lightbox route (/share/{md5}/{name}/p/{photoId}) — fetches a single
    // photo, but only if it actually belongs to this album. ShareAccessFilter already checks the
    // requested album against the guest's authenticated share; this containment check is the
    // second half of "don't serve a photo that isn't part of the shared album."
    @GetMapping("/{id}/photos/{photoId}")
    public ResponseEntity<Photo> getAlbumPhoto(@PathVariable UUID id, @PathVariable UUID photoId) {
        try {
            Photo photo = albumService.getAlbumPhotoForShare(id, photoId);
            return ResponseEntity.ok(photo);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}
