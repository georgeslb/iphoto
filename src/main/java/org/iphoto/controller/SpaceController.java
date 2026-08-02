package org.iphoto.controller;

import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.PageResponse;
import org.iphoto.model.Photo;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.PhotoService;
import org.iphoto.service.SpaceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SpaceController extends AbstractBaseController {

    private SpaceService spaceService;
    private PhotoService photoService;

    public SpaceController(SpaceService spaceService, UserRepository userRepository, PhotoService photoService) {
        super(userRepository);
        this.spaceService = spaceService;
        this.photoService = photoService;
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
        List<SpaceDTO> albums = spaceService.getAllSpaces(currentUser).stream().filter(spaceDTO -> !spaceDTO.deleted()).toList();
        return ResponseEntity.ok(albums);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SpaceDTO> getAlbumById(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        return spaceService.getSpaceByIdForUser(id, currentUser).stream()
                .findAny()
                .map(ResponseEntity::ok)
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSpace(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        spaceService.getSpaceByIdForUser(id, currentUser)
                .filter(SpaceDTO::deletionAllowed)
                .ifPresent((space) -> {
                    //photoService.deletePhotoInSpace(currentUser, space.id());
                    spaceService.markSpaceForDeletion(currentUser, space.id());
                }
        );
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @GetMapping("/garbage")
    public PageResponse<SpaceDTO> getGarbageSpaces(@PageableDefault(size = 50, sort = "garbageAt", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return PageResponse.from(new PageImpl<>(spaceService.getGarbageSpaces(currentUser)));
    }

    @PostMapping("/garbage/restore")
    public ResponseEntity<Void> restoreSpace(@RequestBody List<UUID> ids) {
        UserEntity currentUser = getCurrentUser();
        spaceService.restoreSpacesFromGarbage(currentUser,ids);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/garbage/permanent")
    public ResponseEntity<Void> permanentDeleteAssets(@RequestBody List<UUID> ids) {
        UserEntity currentUser = getCurrentUser();
        ids.forEach(spaceId -> {
            spaceService.getSpaceByIdForUser(spaceId, currentUser)
                    .ifPresent(spaceDTO -> {
                        photoService.deletePhotoInSpace(currentUser, spaceId);
                        spaceService.deleteSpace(spaceId);
                    });

        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{spaceId}/photos")
    public ResponseEntity<Page<Photo>> getSpacePhotos(@PathVariable UUID spaceId, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        try {
            Page<Photo> photos = photoService.getSpacePhotos(currentUser, spaceId, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}