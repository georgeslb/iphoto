package org.iphoto.controller;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.PersonNameCollisionException;
import org.iphoto.exception.PersonNameCollisionsException;
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.request.FaceRenameRequest;
import org.iphoto.model.request.PersonMergeRequest;
import org.iphoto.model.request.PersonRenameRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.FaceService;
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
        try {
            return ResponseEntity.ok(faceService.getFacesForPhoto(currentUser, UUID.fromString(id)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFace(@PathVariable UUID id) {
        try {
            faceService.deleteFace(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/persons")
    public ResponseEntity<Page<PersonDTO>> getPersons(@PageableDefault(size = 50, sort = "name", direction = Sort.Direction.DESC)  Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return ResponseEntity.ok(faceService.getPersons(pageable));
    }

    @GetMapping("/person/{id}")
    public ResponseEntity<PersonDTO> getPerson(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(faceService.getPerson(id));
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    // Renames a whole person (Person page title / Persons grid inline rename — PersonDetail.jsx /
    // Persons.jsx). On a name collision with one or more OTHER people, returns 409 with their DTOs
    // (cover face included) instead of applying anything — the frontend prompts to pick which (if
    // any) to merge in, then retries with confirmed: true.
    @PutMapping("/person/{id}")
    public ResponseEntity<?> renamePerson(@PathVariable UUID id, @RequestBody PersonRenameRequest request) {
        try {
            return ResponseEntity.ok(faceService.renamePerson(id, request.name(), request.confirmed(), request.mergeWithPersonIds()));
        } catch (PersonNameCollisionsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(faceService.getPersons(e.getExistingPersonIds()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/person/{id}/faces")
    public ResponseEntity<Page<FaceDTO>> getPersonFaces(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC)  Pageable pageable) {
        return ResponseEntity.ok(faceService.getPersonFaces(id, pageable));
    }

    @GetMapping("/person/{id}/photos")
    public ResponseEntity<Page<Photo>> getPersonPhotos(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        try {
            Page<Photo> photos = faceService.getPersonPhotos(id, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @DeleteMapping("/person/{personId}/photo/{photoId}")
    public ResponseEntity<Void> unlinkPersonFromPhoto(@PathVariable UUID personId, @PathVariable UUID photoId) {
        try {
            faceService.unlinkPersonFromPhoto(personId, photoId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    // Sets/renames which person a single face maps to (Lightbox "Faces Detected" rename). On a
    // name collision with a DIFFERENT person, returns 409 with that person's info (cover face
    // included) instead of applying anything — the frontend prompts to merge, then retries with
    // confirmMerge: true.
    @PutMapping("/{id}/person")
    public ResponseEntity<?> setFacePerson(@PathVariable UUID id, @RequestBody FaceRenameRequest request) {
        try {
            return ResponseEntity.ok(faceService.setFacePerson(id, request.name(), request.confirmMerge()));
        } catch (PersonNameCollisionException e) {
            try {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(faceService.getPerson(e.getExistingPersonId()));
            } catch (NoSuchElementException notFound) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // Unlinks a single face from its person, keeping the face/detection itself — for correcting
    // one misattributed photo without deleting the face entirely (see FaceService.deleteFace for
    // that separate, full-deletion action).
    @DeleteMapping("/{id}/person")
    public ResponseEntity<Void> unlinkFaceFromPerson(@PathVariable UUID id) {
        try {
            faceService.unlinkFaceFromPerson(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    // Merges two people from the Persons page — the person with fewer faces is folded into the
    // one with more (see PersonRecognitionService.mergePersons); direction isn't caller-chosen.
    @PostMapping("/persons/merge")
    public ResponseEntity<PersonDTO> mergePersons(@RequestBody PersonMergeRequest request) {
        try {
            return ResponseEntity.ok(faceService.mergePersons(request.personIds()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}