package org.iphoto.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.iphoto.entities.CoverFaceOrigin;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FaceRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private static final double THRESHOLD = 0.8;

    public void groupFaces() {
        Pageable pageable = Pageable.ofSize(20);
        List<PersonEntity> persons = personRepository.findAll();
        Page<FaceEntity> facesNotGrouped = faceRepository.findByPersonIsNull(pageable);
        do {
            for (FaceEntity face : facesNotGrouped) {
                checkIfFaceRelatedToPerson(face, persons).ifPresent(personWithScore -> {
                    face.setPerson(personWithScore.getPerson());
                    faceRepository.save(face);
                    personWithScore.getPerson().getFaces().add(face);
                    recalculateCoverFace(personWithScore.getPerson());
                    personRepository.save(personWithScore.getPerson());
                });
            }
            facesNotGrouped = faceRepository.findByPersonIsNull(pageable.next());

        } while (facesNotGrouped.hasNext() && facesNotGrouped.getNumber() <= 5);

        pageable = Pageable.ofSize(100);
        facesNotGrouped = faceRepository.findByPersonIsNull(pageable);


    }

    private Optional<PersonWithScore> checkIfFaceRelatedToPerson(FaceEntity face, List<PersonEntity> persons) {
        Optional<PersonWithScore> selectedPerson = persons
                .stream()
                .flatMap(person -> getPersonWithScoreStream(face, person, 20))
                .min(Comparator.comparingDouble(PersonWithScore::getScore));
        if (selectedPerson.isEmpty()) {
            selectedPerson = persons
                    .stream()
                    .flatMap(person -> getPersonWithScoreStream(face, person, 100))
                    .min(Comparator.comparingDouble(PersonWithScore::getScore));
        }
        return selectedPerson;
    }

    private Stream<PersonWithScore> getPersonWithScoreStream(FaceEntity face, PersonEntity person, int pageSize) {
        PhotoEntity photoWorkInProgressFace = face.getPhoto();
        List<FaceEntity> facesForPersonInDate = faceRepository
                .findByPersonAndPhotoDateTakenBeforeOrderByPhotoDateTakenDesc(person, photoWorkInProgressFace.getDateTaken(), Pageable.ofSize(pageSize))
                .stream().toList();
        return calculateScoreForPerson(face, facesForPersonInDate).stream()
                .filter(score -> score < THRESHOLD)
                .mapToObj(score -> PersonWithScore.builder().person(person).score(score).build());
    }

    /**
     * Finds the best matching face from a map of known faces.
     *
     * @param face The embedding of the face to identify.
     * @param person A Map where the key is the unique ID of a known face and the value is its embedding.
     * @return An Optional containing the ID of the best match, or an empty Optional if no match is found below the threshold.
     */
    private OptionalDouble calculateScoreForPerson(FaceEntity face, List<FaceEntity> person) {
        float[] newEmbeddingFloats = byteArrayToFloatArray(face.getEmbedding());
        return person.stream()
                .map(FaceEntity::getEmbedding)
                .map(this::byteArrayToFloatArray)
                .map(knownEmbeddingFloats -> this.calculateEuclideanDistance(newEmbeddingFloats, knownEmbeddingFloats))
                .mapToDouble(Double::doubleValue)
                .average();
    }


    /**
     * Calculates the L2 Euclidean distance between two vectors.
     */
    private double calculateEuclideanDistance(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension.");
        }
        double sumOfSquares = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            sumOfSquares += Math.pow(vectorA[i] - vectorB[i], 2);
        }
        return Math.sqrt(sumOfSquares);
    }


    /**
     * Converts a byte array back into a float array.
     */
    private float[] byteArrayToFloatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer floatBuffer = buffer.asFloatBuffer();
        float[] floatArray = new float[floatBuffer.remaining()];
        floatBuffer.get(floatArray);
        return floatArray;
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

    @Builder
    @Data
    private static class PersonWithScore {
        private PersonEntity person;
        private double score;
    }
}