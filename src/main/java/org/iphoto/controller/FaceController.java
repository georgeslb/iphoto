package org.iphoto.controller;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.PersonNameCollisionException;
import org.iphoto.exception.PersonNameCollisionsException;
import org.iphoto.model.DayBucketDTO;
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PageResponse;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.PersonMergeSuggestionDTO;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    // Sort order (named people first, then photoCount descending) is fixed server-side -
    // FaceServiceImpl.getPersons strips any Sort off this Pageable before querying, so a caller
    // can't override it via ?sort=; only page/size are honored from here.
    @GetMapping("/persons")
    public ResponseEntity<Page<PersonDTO>> getPersons(@PageableDefault(size = 50) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return ResponseEntity.ok(faceService.getPersons(pageable));
    }

    // Library page banner/popup (see PersonMergeSuggestionService, specification.md's Persons
    // View section) — one page of the current user's own live "these two people might be the
    // same" suggestions, most-similar first. Paginated at size=1 (the popup only ever shows one
    // pair — PersonMergeSuggestionModal.jsx) rather than the flat, unpaginated list this used to
    // be: each entry embeds BOTH persons' full base64 cover-face thumbnails
    // (FaceServiceImpl#mapToDTO), so fetching every suggestion up front to show only one at a time
    // meant paying for every other pair's face images too — a real library with many suggestions
    // turned that into a multi-megabyte, multi-second response for a banner that, until opened,
    // only needs a count (see getPersonMergeSuggestionsCount below).
    @GetMapping("/persons/merge-suggestions")
    public PageResponse<PersonMergeSuggestionDTO> getPersonMergeSuggestions(@PageableDefault(size = 1) Pageable pageable) {
        return PageResponse.from(faceService.getMergeSuggestions(getCurrentUser(), pageable));
    }

    // Cheap count-only endpoint for the banner text ("N people might be duplicates") — no face
    // thumbnails, not even one suggestion's worth.
    @GetMapping("/persons/merge-suggestions/count")
    public Map<String, Long> getPersonMergeSuggestionsCount() {
        return Map.of("count", faceService.countMergeSuggestions(getCurrentUser()));
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
    public ResponseEntity<Page<FaceDTO>> getPersonFaces(@PathVariable UUID id, @PageableDefault(size = 50, sort = "photo.dateTaken", direction = Sort.Direction.DESC)  Pageable pageable) {
        return ResponseEntity.ok(faceService.getPersonFaces(id, pageable));
    }

    // Day-index browsing for Person (replaces the old flat GET /person/{id}/photos) - every day
    // that has one of this person's photos, fetched up front, then each day's photos lazily as
    // its section scrolls into view. See PhotoController's Library/Garbage siblings for the same
    // pattern.
    @GetMapping("/person/{id}/days")
    public ResponseEntity<List<DayBucketDTO>> getPersonDays(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(faceService.getPersonDays(id));
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/person/{id}/photos/day/{day}")
    public ResponseEntity<Page<Photo>> getPersonPhotosForDay(@PathVariable UUID id, @PathVariable String day,
                                                              @PageableDefault(size = 100, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        try {
            Page<Photo> photos = faceService.getPersonPhotosForDay(id, LocalDate.parse(day), pageable);
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