package org.iphoto.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.iphoto.entities.CoverFaceOrigin;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PersonRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private static final double THRESHOLD = 0.8;

    public void updatePerson(UserEntity user, PersonEntity person, FaceEntity face) { // Added UserEntity parameter
        // Ensure the person and face belong to the user
        if (!person.getUser().equals(user)) {
            throw new IllegalArgumentException("Person does not belong to the current user.");
        }
        if (!face.getUser().equals(user)) {
            throw new IllegalArgumentException("Face does not belong to the current user.");
        }

        face.setPerson(person);
        faceRepository.save(face);
        person.getFaces().add(face);
        recalculateCoverFace(person);
        personRepository.save(person);
    }

    public void addPerson(UserEntity user, List<FaceEntity> faces) { // Added UserEntity parameter
        PersonEntity person = new PersonEntity();
        person.setUser(user); // Set the user for the new person
        person.setFaces(faces);
        recalculateCoverFace(person);
        faces.forEach(faceEntity -> {
            if (!faceEntity.getUser().equals(user)) { // Ensure all faces belong to the user
                throw new IllegalArgumentException("One or more faces do not belong to the current user.");
            }
            faceEntity.setPerson(person);
        });
        personRepository.save(person);
        faceRepository.saveAll(faces);
    }

    /**
     * Helper method to recalculate and update the cover face based on the most recent valid photo date.
     * A photo date is considered valid if dateTaken is not null and is different from createdAt.
     * This method only updates the cover face if its current origin is not MANUAL.
     */
    private void recalculateCoverFace(PersonEntity person) {
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