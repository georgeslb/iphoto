package org.iphoto.service;

import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.DayBucketDTO;
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.PersonMergeSuggestionDTO;
import org.iphoto.model.Photo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceService {

    // FaceRepository.findByPhotoId isn't (and can't be) @MainSpaceLimited — FaceEntity has no
    // space_id column for Hibernate's spaceFilter to apply to (it belongs to a photo, which has
    // one). Ownership has to be checked explicitly here instead, the same way
    // PhotoController.getPhoto does it — otherwise this returns any user's face thumbnails for
    // any photo id, guessable or not.
    List<FaceDTO> getFacesForPhoto(UserEntity user, UUID photoId);

    Page<PersonDTO> getPersons(Pageable pageable);

    PersonDTO getPerson(UUID id);

    Page<FaceDTO> getPersonFaces(UUID id, Pageable pageable);

    // Day-index browsing for Person (replaces the old flat GET /api/faces/person/{id}/photos) -
    // see PhotoService.getPersonDailyPhotoCounts/getPersonPhotosForDay.
    List<DayBucketDTO> getPersonDays(UUID id);

    Page<Photo> getPersonPhotosForDay(UUID id, LocalDate day, Pageable pageable);

    // Renames a whole person — the Person page title or the Persons grid's inline rename (as
    // opposed to setFacePerson below, which reassigns a single face). Semantics (confirmed with
    // the user):
    //   - No other person already has this name: plain rename, nothing else happens.
    //   - One or more OTHER people already have this name: without `confirmed`, rejects with
    //     PersonNameCollisionsException carrying all of them, so the caller can prompt "merge
    //     with one (or more) of these?" showing each candidate's own face. With `confirmed`, the
    //     person being renamed is ALWAYS the keeper — every id in `mergeWithPersonIds` (the ones
    //     the user actually picked, possibly none) is folded into it and deleted, fixed direction,
    //     unlike the face-count-decided /persons/merge flow.
    PersonDTO renamePerson(UUID personId, String name, boolean confirmed, List<UUID> mergeWithPersonIds);

    // Backs the 409 response of renamePerson above — re-fetches the full DTOs (cover face, photo
    // count) for a PersonNameCollisionsException's candidate ids, so mapping logic stays in
    // mapToPerson instead of duplicated in the controller.
    List<PersonDTO> getPersons(List<UUID> ids);

    Optional<FaceEntity> findById(UserEntity user, UUID id);

    void deleteFace(UUID id);

    void unlinkPersonFromPhoto(UUID personId, UUID photoId);

    // Unlinks a single face from its current person, keeping the face itself (its detection box,
    // thumbnail and embedding are untouched) — for when one specific photo/face was
    // misattributed, without wanting to lose the face entirely (that's the separate deleteFace).
    void unlinkFaceFromPerson(UUID faceId);

    // Sets/changes which person a single face maps to, resolved by name — the Lightbox "Faces
    // Detected" rename affordance. Semantics (confirmed with the user):
    //   - No OTHER person already has this name: renames the face's current person outright (all
    //     of their faces relabel, since it's the same identity) — or, if the face had no person
    //     yet, creates a brand-new one for just this face.
    //   - A DIFFERENT person already has this name: this is a collision, not a plain rename.
    //     Without confirmMerge, throws PersonNameCollisionException so the caller can prompt
    //     ("merge into {existing person}?"). With confirmMerge, moves ONLY this one face to that
    //     existing person — the rest of its previous person's faces are untouched (a full
    //     person-to-person merge is the separate, explicit /persons/merge endpoint).
    FaceDTO setFacePerson(UUID faceId, String name, boolean confirmMerge);

    // Merges two people (the Persons page multi-select action) — thin delegate to
    // PersonRecognitionService.mergePersons so FaceController's dependencies stay unchanged.
    PersonDTO mergePersons(List<UUID> personIds);

    // Library page banner/popup (see PersonMergeSuggestionService) — every live "these two people
    // might be the same" suggestion for the current user's own space, most-similar first.
    List<PersonMergeSuggestionDTO> getMergeSuggestions(UserEntity user);
}
