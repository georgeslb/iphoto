package org.iphoto.controller;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.*;
import org.iphoto.repository.UserRepository;
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
@RequestMapping("/api/albums/share")
@Slf4j
public class AlbumShareController extends AbstractBaseController {

    private final AlbumService albumService;

    public AlbumShareController(AlbumService albumService, UserRepository userRepository) {
        super(userRepository);
        this.albumService = albumService;
    }



    @GetMapping("/{id}/photos")
    public ResponseEntity<List<Photo>> getAlbumPhotos(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        try {
            UserEntity currentUser = getCurrentUser();
        } catch (NoSuchElementException e) {
            log.warn("anonymous user fetching shared album");
        }

        try {
            List<Photo> photos = albumService.getAlbumPhotos(id, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}