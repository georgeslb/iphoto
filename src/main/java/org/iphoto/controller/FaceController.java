package org.iphoto.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.Photo;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.FaceService;
import org.iphoto.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/faces")
@Slf4j
public class FaceController extends AbstractBaseController {

    private final FaceService faceService;

    public FaceController(FaceService faceService, UserRepository userRepository) {
        super(userRepository);
        this.faceService = faceService;
    }


    @GetMapping("/photo/{id}")
    public ResponseEntity<List<FaceDTO>> getFacesForPhoto(@PathVariable String id) {
        UserEntity currentUser = getCurrentUser();
        return ResponseEntity.ok(faceService.getFacesForPhoto(currentUser, UUID.fromString(id)));
    }

    @GetMapping("/persons")
    public ResponseEntity<Page<PersonDTO>> getPersons(@PageableDefault(size = 50, sort = "name", direction = Sort.Direction.DESC)  Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return ResponseEntity.ok(faceService.getPersons(currentUser, pageable));
    }

    @PutMapping("/person/{id}")
    public void updatePerson(@PathVariable UUID id, @RequestBody PersonDTO personDTO) {
        UserEntity currentUser = getCurrentUser();
        faceService.updatePerson(currentUser, id, personDTO);
    }

    @GetMapping("/person/{id}/faces")
    public ResponseEntity<Page<FaceDTO>> getPersonFaces(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC)  Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return ResponseEntity.ok(faceService.getPersonFaces(currentUser, id, pageable));
    }

    @GetMapping("/person/{id}/photos")
    public ResponseEntity<Page<Photo>> getPersonPhotos(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        try {
            Page<Photo> photos = faceService.getPersonPhotos(currentUser, id, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}