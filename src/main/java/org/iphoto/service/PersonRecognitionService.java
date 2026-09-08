package org.iphoto.service;

import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;

import java.util.List;
import java.util.UUID;

public interface PersonRecognitionService {

    void updatePerson(PersonEntity person, FaceEntity face);

    // The new person's space is derived from `faces` themselves (the first face's photo's space -
    // see the impl), not from a separate "which user does this belong to" argument: `faces` is
    // never empty (callers only promote once a real cluster of matching faces exists) and is
    // always already scoped to one single space by construction, so re-deriving it from the data
    // being attached is both simpler and strictly more correct than trusting a caller to separately
    // pass the right owner.
    void addPerson(List<FaceEntity> faces);

    // Merges two people into one: the person with FEWER faces (the "source") has every one of its
    // faces reassigned to the person with more faces (the "target"), then the now-empty source is
    // deleted. Direction is decided by face count, not chosen by the caller — this is the
    // dedicated /persons/merge flow (distinct from FaceService.setFacePerson's single-face
    // reassignment): the whole identity collapses into whichever side already had more evidence.
    PersonEntity mergePersons(UUID personId1, UUID personId2);

    // The fixed-direction counterpart to mergePersons above — used by FaceService.renamePerson's
    // rename-collision flow (Person page / Persons grid rename, see PersonDetail.jsx/Persons.jsx):
    // when a rename collides with one or more existing same-named people and the user picks which
    // to fold in, the KEEPER is always the person being renamed, never chosen by face count like
    // the /persons/merge flow above — the user explicitly picked it by renaming it.
    PersonEntity mergeInto(UUID targetPersonId, UUID sourcePersonId);

    /**
     * Recalculates and updates the cover face based on the most recent valid photo date.
     * A photo date is considered valid if dateTaken is not null and is different from createdAt.
     * This method only updates the cover face if its current origin is not MANUAL.
     */
    void recalculateCoverFace(PersonEntity person);

    // ---------------------------------------------------------------
    // PersonEmbeddingBucketEntity maintenance - see that class's own comment for why this exists.
    // Must be called at every point a face is attached to or detached from a person: updatePerson/
    // addPerson/mergePersons/mergeInto above, and FaceService's attachFaceToPerson/
    // detachFaceFromPerson/deleteFace.
    // ---------------------------------------------------------------

    void addFaceToBucket(PersonEntity person, FaceEntity face);

    void removeFaceFromBucket(PersonEntity person, FaceEntity face);

    // PersonEntity.photoCount maintenance - a persisted, incrementally-maintained column (the same
    // recompute-on-write pattern AlbumEntity.photoCount/AlbumServiceImpl.setAlbumPhotoCount already
    // use, rather than SpaceEntity's atomic-delta one) backing the Persons page's fixed named-first/
    // photoCount-DESC sort (PersonRepository's custom query needs a real queryable column to sort
    // by - photoCount used to only ever be computed per-request, see FaceServiceImpl.mapToPerson).
    // Re-runs a fresh COUNT(DISTINCT photo) query rather than tracking a running delta, so it's
    // self-correcting regardless of call order - must be called (and the person saved afterward)
    // at every point a face is attached to or detached from a person, same set of call sites as the
    // bucket-maintenance methods above: updatePerson/addPerson/mergeFacesIntoTarget here, and
    // FaceService's attachFaceToPerson/detachFaceFromPerson/deleteFace, and
    // FaceDetectionService's deleteFaces (whole-photo deletion).
    void recalculatePhotoCount(PersonEntity person);
}
