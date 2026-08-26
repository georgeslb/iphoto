package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.*;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PersonRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final SpaceService spaceService;

    public void updatePerson(PersonEntity person, FaceEntity face) { // Added UserEntity parameter
        face.setPerson(person);
        faceRepository.save(face);
        person.getFaces().add(face);
        recalculateCoverFace(person);
        personRepository.save(person);
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
}