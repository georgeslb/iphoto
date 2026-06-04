package org.iphoto.controller;

import org.iphoto.entities.UserEntity;
import org.iphoto.model.Photo;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.SpaceService;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/spaces")
public class SpaceController extends AbstractBaseController {

    private final SpaceService spaceService;

    public SpaceController(SpaceService spaceService, UserRepository userRepository) {
        super(userRepository);
        this.spaceService = spaceService;
    }

    @PostMapping
    public ResponseEntity<SpaceDTO> createSpace(@RequestBody SpaceCreateRequest spaceCreateRequest) {
        UserEntity currentUser = getCurrentUser();
        try {
            SpaceDTO space = spaceService.createSpace(currentUser, spaceCreateRequest);
            return new ResponseEntity<>(space, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.CONFLICT); // Album with name already exists
        }
    }

    @GetMapping
    public ResponseEntity<List<SpaceDTO>> getAllSpaces() {
        UserEntity currentUser = getCurrentUser();
        List<SpaceDTO> albums = spaceService.getAllSpaces(currentUser);
        return ResponseEntity.ok(albums);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SpaceDTO> getAlbumById(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        return spaceService.getAllSpaces(currentUser).stream().filter(spaceDTO -> spaceDTO.id().equals(id))
                .findAny()
                .map(ResponseEntity::ok)
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSpace(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        spaceService.deleteSpace(currentUser, id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }


    @GetMapping("/{spaceId}/photos")
    public ResponseEntity<Page<Photo>> getSpacePhotos(@PathVariable UUID spaceId, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        try {
            Page<Photo> photos = spaceService.getSpacePhotos(currentUser, spaceId, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}