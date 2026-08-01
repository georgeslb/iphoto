package org.iphoto.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class FaceRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final PersonRecognitionService personRecognitionService;
    private final UserRepository userRepository;
    private final SpaceService spaceService;
    private final ReentrantLock lock = new ReentrantLock();

    // Threshold for Cosine Similarity: A higher value means a stricter match.
    private static final double THRESHOLD = 0.60;

    public void groupFaces() { // Added UserEntity parameter

        lock.lock();
        
        try {
            userRepository.findAll().forEach(this::groupFacesForUser);
        } finally {
            lock.unlock();
        }

    }

    private void groupFacesForUser(UserEntity user) {
        Pageable pageable = Pageable.ofSize(20);
        List<PersonEntity> persons = personRepository.findAll();
        Page<FaceEntity> facesNotGrouped;
        do {
            facesNotGrouped = faceRepository.findByPersonIsNull(pageable);
            for (FaceEntity face : facesNotGrouped) {
                // Find the best matching person and update if the score is above the threshold
                findBestMatchForFace(face, persons).ifPresent(personWithScore -> {
                    personRecognitionService.updatePerson(personWithScore.getPerson(), face);
                });
            }
            pageable = facesNotGrouped.nextPageable();
        } while (facesNotGrouped.hasNext() && facesNotGrouped.getNumber() <= 5);

        pageable = Pageable.ofSize(100);
        facesNotGrouped = faceRepository.findByPersonIsNull(pageable);
        List<List<FaceEntity>> facesGrouped = clusterFaces(facesNotGrouped.toList()); // Pass user
        facesGrouped.forEach(cluster -> personRecognitionService.addPerson(user, cluster)); // Pass user
    }

    private Optional<PersonWithScore> findBestMatchForFace(FaceEntity face, List<PersonEntity> persons) { // Added UserEntity parameter
        // Find the person with the highest similarity score.
        Optional<PersonWithScore> bestMatch = persons.stream()
                .flatMap(person -> getPersonWithScoreStream(face, person, 20)) // Pass user
                .max(Comparator.comparingDouble(PersonWithScore::getScore));

        // If no match was found with the first batch, try a larger sample.
        if (bestMatch.isEmpty()) {
            bestMatch = persons.stream()
                    .flatMap(person -> getPersonWithScoreStream(face, person, 100)) // Pass user
                    .max(Comparator.comparingDouble(PersonWithScore::getScore));
        }

        // Only return the match if it's above our confidence threshold.
        return bestMatch.filter(match -> match.getScore() >= THRESHOLD);
    }

    private Stream<PersonWithScore> getPersonWithScoreStream(FaceEntity face, PersonEntity person, int pageSize) {
        PhotoEntity photoWorkInProgressFace = face.getPhoto();
        List<FaceEntity> facesForPersonInDate = faceRepository
                .findByPersonAndPhotoDateTakenBeforeOrderByPhotoDateTakenDesc(person, photoWorkInProgressFace.getDateTaken(), Pageable.ofSize(pageSize)) // Filter by user
                .stream().toList();

        // Calculate the average similarity and map to a PersonWithScore object.
        return calculateAverageSimilarity(face, facesForPersonInDate)
                .stream()
                .mapToObj(score -> PersonWithScore.builder().person(person).score(score).build());
    }

    private OptionalDouble calculateAverageSimilarity(FaceEntity face, List<FaceEntity> personFaces) {
        if (personFaces.isEmpty()) {
            return OptionalDouble.empty();
        }
        float[] newEmbeddingFloats = byteArrayToFloatArray(face.getEmbedding());
        return personFaces.stream()
                .map(FaceEntity::getEmbedding)
                .map(this::byteArrayToFloatArray)
                .mapToDouble(knownEmbeddingFloats -> this.cosineSimilarity(newEmbeddingFloats, knownEmbeddingFloats))
                .average();
    }

    /**
     * Calculates the Cosine Similarity between two vectors.
     * The result is between -1 and 1, where 1 means the vectors are identical.
     */
    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension.");
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        if (normA == 0 || normB == 0) {
            return 0; // Avoid division by zero
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private float[] byteArrayToFloatArray(byte[] bytes) {
        if (bytes == null) return new float[0];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        // **FIX**: Set the byte order to LITTLE_ENDIAN to match the default writing order.
        buffer.order(ByteOrder.BIG_ENDIAN);
        FloatBuffer floatBuffer = buffer.asFloatBuffer();
        float[] floatArray = new float[floatBuffer.remaining()];
        floatBuffer.get(floatArray);
        return floatArray;
    }

    private List<List<FaceEntity>> clusterFaces(List<FaceEntity> facesToCluster) { // Added UserEntity parameter
        if (CollectionUtils.isEmpty(facesToCluster)) {
            return new ArrayList<>();
        }

        List<List<FaceEntity>> clusters = new ArrayList<>();
        boolean[] isClustered = new boolean[facesToCluster.size()];

        for (int i = 0; i < facesToCluster.size(); i++) {
            if (isClustered[i]) continue;

            FaceEntity currentFace = facesToCluster.get(i);
            if (currentFace.getEmbedding() == null) continue;

            List<FaceEntity> newCluster = new ArrayList<>();
            newCluster.add(currentFace);
            isClustered[i] = true;

            float[] currentEmbedding = byteArrayToFloatArray(currentFace.getEmbedding());

            for (int j = i + 1; j < facesToCluster.size(); j++) {
                if (isClustered[j]) continue;

                FaceEntity otherFace = facesToCluster.get(j);
                if (otherFace.getEmbedding() == null) continue;

                float[] otherEmbedding = byteArrayToFloatArray(otherFace.getEmbedding());
                double similarity = cosineSimilarity(currentEmbedding, otherEmbedding);

                if (similarity >= THRESHOLD) {
                    newCluster.add(otherFace);
                    isClustered[j] = true;
                }
            }
            if (newCluster.size() >= 3) {
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