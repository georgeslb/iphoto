package org.iphoto.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
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
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FaceRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final PersonRecognitionService personRecognitionService;

    private static final double THRESHOLD = 0.8;

    public void groupFaces() {
        Pageable pageable = Pageable.ofSize(20);
        List<PersonEntity> persons = personRepository.findAll();
        Page<FaceEntity> facesNotGrouped;
        do {
            facesNotGrouped = faceRepository.findByPersonIsNull(pageable);
            for (FaceEntity face : facesNotGrouped) {
                checkIfFaceRelatedToPerson(face, persons).ifPresent(personWithScore -> {
                    personRecognitionService.updatePerson(personWithScore.getPerson(), face);
                });
            }
            pageable = facesNotGrouped.nextPageable();
        } while (facesNotGrouped.hasNext() && facesNotGrouped.getNumber() <= 5);

        pageable = Pageable.ofSize(100);
        facesNotGrouped = faceRepository.findByPersonIsNull(pageable);
        List<List<FaceEntity>> facestGrouped =  clusterFaces(facesNotGrouped.toList());
        facestGrouped.forEach(personRecognitionService::addPerson);
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
     * Groups a list of faces into clusters based on their embeddings.
     * This is ideal for an initial run to discover people from a large set of ungrouped faces.
     *
     * @param facesToCluster A list of FaceEntity objects, each with a valid embedding.
     * @return A list of lists, where each inner list is a cluster of faces belonging to the same person.
     */
    private List<List<FaceEntity>> clusterFaces(List<FaceEntity> facesToCluster) {
        if (CollectionUtils.isEmpty(facesToCluster)) {
            return new ArrayList<>();
        }

        List<List<FaceEntity>> clusters = new ArrayList<>();
        // Keep track of which faces have already been assigned to a cluster
        boolean[] isClustered = new boolean[facesToCluster.size()];

        for (int i = 0; i < facesToCluster.size(); i++) {
            if (isClustered[i]) {
                continue; // This face is already in a cluster
            }

            FaceEntity currentFace = facesToCluster.get(i);
            if (currentFace.getEmbedding() == null) {
                continue; // Cannot cluster a face without an embedding
            }

            // Start a new cluster with the current face
            List<FaceEntity> newCluster = new ArrayList<>();
            newCluster.add(currentFace);
            isClustered[i] = true;

            float[] currentEmbedding = byteArrayToFloatArray(currentFace.getEmbedding());

            // Compare with all other faces to see if they belong in this new cluster
            for (int j = i + 1; j < facesToCluster.size(); j++) {
                if (isClustered[j]) {
                    continue;
                }

                FaceEntity otherFace = facesToCluster.get(j);
                if (otherFace.getEmbedding() == null) {
                    continue;
                }

                float[] otherEmbedding = byteArrayToFloatArray(otherFace.getEmbedding());
                double distance = calculateEuclideanDistance(currentEmbedding, otherEmbedding);

                if (distance <= THRESHOLD) {
                    // It's a match, add it to the current cluster
                    newCluster.add(otherFace);
                    isClustered[j] = true;
                }
            }
            if (newCluster.size() > 2) {
                clusters.add(newCluster);
            }
        }

        return clusters;
    }

    @Builder
    @Data
    private static class PersonWithScore {
        private PersonEntity person;
        private double score;
    }
}