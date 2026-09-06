package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.*;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonEmbeddingBucketRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.util.EmbeddingCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PersonRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final SpaceService spaceService;
    private final PersonEmbeddingBucketRepository personEmbeddingBucketRepository;

    public void updatePerson(PersonEntity person, FaceEntity face) { // Added UserEntity parameter
        face.setPerson(person);
        faceRepository.save(face);
        person.getFaces().add(face);
        recalculateCoverFace(person);
        personRepository.save(person);
        addFaceToBucket(person, face);
    }

    public void addPerson(UserEntity user, List<FaceEntity> faces) { // Added UserEntity parameter
        PersonEntity newPerson = PersonEntity.builder().space(spaceService.getDefaultSpaceForUser(user)).faces(faces).build();
        recalculateCoverFace(newPerson);
        // Flush the brand-new person immediately, before any face is made to reference it: faces
        // in `faces` are detached (loaded earlier, outside this transaction), so saving them
        // triggers a merge, and merge needs the referenced person to already be a genuinely
        // persisted row — not just a pending, not-yet-flushed insert — or Hibernate throws
        // TransientObjectException at commit time ("references an unsaved transient instance").
        PersonEntity person = personRepository.saveAndFlush(newPerson);
        faces.forEach(faceEntity -> faceEntity.setPerson(person));
        faceRepository.saveAll(faces);
        faces.forEach(face -> addFaceToBucket(person, face));
    }

    // Merges two people into one: the person with FEWER faces (the "source") has every one of its
    // faces reassigned to the person with more faces (the "target"), then the now-empty source is
    // deleted. Direction is decided by face count, not chosen by the caller — this is the
    // dedicated /persons/merge flow (distinct from FaceService.setFacePerson's single-face
    // reassignment): the whole identity collapses into whichever side already had more evidence.
    @Transactional
    public PersonEntity mergePersons(UUID personId1, UUID personId2) {
        PersonEntity person1 = personRepository.findById(personId1)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + personId1));
        PersonEntity person2 = personRepository.findById(personId2)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + personId2));

        PersonEntity source = person1.getFaces().size() <= person2.getFaces().size() ? person1 : person2;
        PersonEntity target = source == person1 ? person2 : person1;

        return mergeFacesIntoTarget(source, target);
    }

    // The fixed-direction counterpart to mergePersons above — used by FaceService.renamePerson's
    // rename-collision flow (Person page / Persons grid rename, see PersonDetail.jsx/Persons.jsx):
    // when a rename collides with one or more existing same-named people and the user picks which
    // to fold in, the KEEPER is always the person being renamed, never chosen by face count like
    // the /persons/merge flow above — the user explicitly picked it by renaming it.
    @Transactional
    public PersonEntity mergeInto(UUID targetPersonId, UUID sourcePersonId) {
        PersonEntity target = personRepository.findById(targetPersonId)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + targetPersonId));
        PersonEntity source = personRepository.findById(sourcePersonId)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + sourcePersonId));

        return mergeFacesIntoTarget(source, target);
    }

    // Shared by mergePersons and mergeInto: moves every face from source to target, recalculates
    // target's cover face, and deletes the now-empty source. No FaceLinkExclusionEntity bookkeeping
    // here — unlike an unlink, a merge is an explicit "these are the same person" declaration, so
    // future auto-matching against the target should freely include these faces, exactly as if
    // they'd always belonged to it.
    private PersonEntity mergeFacesIntoTarget(PersonEntity source, PersonEntity target) {
        List<FaceEntity> facesToMove = new ArrayList<>(source.getFaces());
        facesToMove.forEach(face -> {
            face.setPerson(target);
            target.getFaces().add(face);
        });
        source.getFaces().clear();
        faceRepository.saveAll(facesToMove);

        recalculateCoverFace(target);
        personRepository.save(target);

        // Fold source's bucket contribution into target's directly (merging averages bucket-by-
        // bucket) instead of re-decoding every individual face's embedding again - source's own
        // buckets are cascade-deleted below along with source itself (see PersonEntity#
        // embeddingBuckets), so nothing else needs to clean them up.
        mergeBuckets(source, target);

        personRepository.delete(source);

        return target;
    }

    /**
     * Helper method to recalculate and update the cover face based on the most recent valid photo date.
     * A photo date is considered valid if dateTaken is not null and is different from createdAt.
     * This method only updates the cover face if its current origin is not MANUAL.
     */
    void recalculateCoverFace(PersonEntity person) {
        if (person.getCoverFaceOrigin() == CoverFaceOrigin.MANUAL) {
            return; // Never override a manual choice automatically
        }

        FaceEntity bestFace = getLastFaceForPerson(person);

        // If we found a valid face, update the cover
        if (bestFace != null) {
            person.setCoverFace(bestFace);
        } else if (!CollectionUtils.isEmpty(person.getFaces()) && person.getCoverFace() == null) {
            // Fallback: If no photo has a valid dateTaken and we don't have a cover face yet,
            // just pick the first face so the UI isn't broken.
            person.setCoverFace(person.getFaces().getFirst());
        }
        person.setCoverFaceOrigin(CoverFaceOrigin.CALCULATED);
    }


    private FaceEntity getLastFaceForPerson(PersonEntity person) {
        FaceEntity bestFace = null;
        LocalDateTime latestDate = null;

        for (FaceEntity face : person.getFaces()) {
            PhotoEntity photo = face.getPhoto();
            if (photo != null) {
                LocalDateTime dateTaken = photo.getDateTaken();
                LocalDateTime createdAt = photo.getCreatedAt();

                // Check if dateTaken is valid and different from createdAt
                if (dateTaken != null && !dateTaken.equals(createdAt)) {
                    if (latestDate == null || dateTaken.isAfter(latestDate)) {
                        latestDate = dateTaken;
                        bestFace = face;
                    }
                }
            }
        }
        return bestFace;
    }

    // ---------------------------------------------------------------
    // PersonEmbeddingBucketEntity maintenance - see that class's own comment for why this exists.
    // Must be called at every point a face is attached to or detached from a person: updatePerson/
    // addPerson/mergeFacesIntoTarget above, and FaceService's attachFaceToPerson/
    // detachFaceFromPerson/deleteFace.
    // ---------------------------------------------------------------

    void addFaceToBucket(PersonEntity person, FaceEntity face) {
        int bucketYear = bucketYearFor(face);
        PersonEmbeddingBucketEntity bucket = personEmbeddingBucketRepository
                .findByPersonIdAndBucketYear(person.getId(), bucketYear)
                .orElseGet(() -> PersonEmbeddingBucketEntity.builder()
                        .person(person).bucketYear(bucketYear).faceCount(0).embedding(new byte[0]).build());

        float[] incoming = EmbeddingCodec.toFloats(face.getEmbedding());
        float[] updated = bucket.getFaceCount() == 0
                ? incoming
                : weightedAverage(EmbeddingCodec.toFloats(bucket.getEmbedding()), bucket.getFaceCount(), incoming);
        bucket.setEmbedding(EmbeddingCodec.toBytes(updated));
        bucket.setFaceCount(bucket.getFaceCount() + 1);
        personEmbeddingBucketRepository.save(bucket);
    }

    void removeFaceFromBucket(PersonEntity person, FaceEntity face) {
        int bucketYear = bucketYearFor(face);
        personEmbeddingBucketRepository.findByPersonIdAndBucketYear(person.getId(), bucketYear)
                .ifPresent(bucket -> {
                    if (bucket.getFaceCount() <= 1) {
                        // Last (or only, if counts ever drifted) face leaving this bucket - nothing
                        // meaningful left to average, so drop the row rather than leave a
                        // zero-count or divide-by-zero husk behind.
                        personEmbeddingBucketRepository.delete(bucket);
                        return;
                    }
                    float[] outgoing = EmbeddingCodec.toFloats(face.getEmbedding());
                    float[] updated = weightedRemove(EmbeddingCodec.toFloats(bucket.getEmbedding()), bucket.getFaceCount(), outgoing);
                    bucket.setEmbedding(EmbeddingCodec.toBytes(updated));
                    bucket.setFaceCount(bucket.getFaceCount() - 1);
                    personEmbeddingBucketRepository.save(bucket);
                });
    }

    // Folds every one of source's buckets into target's matching-year bucket (creating it if
    // target has none for that year yet) - source's own bucket rows are left alone here, they're
    // cascade-deleted when the caller deletes the now-empty source PersonEntity afterward.
    private void mergeBuckets(PersonEntity source, PersonEntity target) {
        for (PersonEmbeddingBucketEntity sourceBucket : personEmbeddingBucketRepository.findByPersonId(source.getId())) {
            PersonEmbeddingBucketEntity targetBucket = personEmbeddingBucketRepository
                    .findByPersonIdAndBucketYear(target.getId(), sourceBucket.getBucketYear())
                    .orElseGet(() -> PersonEmbeddingBucketEntity.builder()
                            .person(target).bucketYear(sourceBucket.getBucketYear()).faceCount(0).embedding(new byte[0]).build());

            float[] sourceVector = EmbeddingCodec.toFloats(sourceBucket.getEmbedding());
            float[] merged = targetBucket.getFaceCount() == 0
                    ? sourceVector
                    : weightedAverage(EmbeddingCodec.toFloats(targetBucket.getEmbedding()), targetBucket.getFaceCount(),
                            sourceVector, sourceBucket.getFaceCount());
            targetBucket.setEmbedding(EmbeddingCodec.toBytes(merged));
            targetBucket.setFaceCount(targetBucket.getFaceCount() + sourceBucket.getFaceCount());
            personEmbeddingBucketRepository.save(targetBucket);
        }
    }

    private static int bucketYearFor(FaceEntity face) {
        return PersonEmbeddingBucketEntity.bucketYearFor(face);
    }

    private static float[] weightedAverage(float[] current, int currentCount, float[] incoming) {
        float[] result = new float[current.length];
        int total = currentCount + 1;
        for (int i = 0; i < current.length; i++) {
            result[i] = (current[i] * currentCount + incoming[i]) / total;
        }
        return result;
    }

    private static float[] weightedAverage(float[] a, int countA, float[] b, int countB) {
        float[] result = new float[a.length];
        int total = countA + countB;
        for (int i = 0; i < a.length; i++) {
            result[i] = (a[i] * countA + b[i] * countB) / total;
        }
        return result;
    }

    private static float[] weightedRemove(float[] current, int currentCount, float[] outgoing) {
        float[] result = new float[current.length];
        int remaining = currentCount - 1;
        for (int i = 0; i < current.length; i++) {
            result[i] = (current[i] * currentCount - outgoing[i]) / remaining;
        }
        return result;
    }
}