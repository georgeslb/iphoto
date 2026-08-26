package org.iphoto.service;

import jakarta.persistence.EntityManager;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.UserRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class FaceRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final PersonRecognitionService personRecognitionService;
    private final UserRepository userRepository;
    private final SpaceService spaceService;
    private final ReentrantLock lock = new ReentrantLock();

    // Threshold for Cosine Similarity: A higher value means a stricter match. Kept strict and
    // never loosened for "same person, different age" cases — a face at 5 and the same face at
    // 25 are NOT expected to clear this directly. Instead, a rich timeline of in-between photos
    // lets matches chain through nearby ages (see TEMPORAL_NEIGHBOR_WINDOW below), so long-range
    // drift is handled by transitivity, not by weakening the per-pair confidence bar.
    private static final double THRESHOLD = 0.60;

    // How many of a candidate's faces closest in time (both before AND after the face being
    // matched) are considered per comparison, instead of comparing against a person's whole
    // history (which dilutes the score across eras) or only ever looking at earlier photos (the
    // previous behavior). Bounds cost to O(faces * persons * window) instead of O(faces *
    // total_faces_across_all_persons), and keeps each comparison set temporally coherent.
    private static final int TEMPORAL_NEIGHBOR_WINDOW = 10;

    // Cap on ungrouped faces processed in a single run. groupFaces() reruns every ~30s
    // (TaskWorkerManager's FACE_RECOGNITION task), so a backlog beyond this is simply picked up
    // by later runs — this just bounds memory/cost per run rather than being a correctness limit.
    private static final int MAX_UNGROUPED_FACES_PER_RUN = 2000;

    // Minimum faces required for a brand-new (not-yet-matched-to-an-existing-person) cluster to
    // become a PersonEntity, to avoid spurious 1-2 face "people" from noise/coincidental matches.
    private static final int MIN_NEW_PERSON_CLUSTER_SIZE = 3;
    private final EntityManager entityManager;

    public void groupFaces() { // Added UserEntity parameter

        lock.lock();

        try {
            userRepository.findAll().stream().filter(userEntity -> !userEntity.getRole().equals("ADMIN")).forEach(user -> {
                groupFacesForUser(user);
                entityManager.flush();
            });
        } finally {
            lock.unlock();
        }

    }

    private void groupFacesForUser(UserEntity user) {
        try {
            List<PersonEntity> persons = personRepository.findAll();
            List<FaceEntity> ungroupedFaces = faceRepository
                    .findByPersonIsNull(Pageable.ofSize(MAX_UNGROUPED_FACES_PER_RUN))
                    .stream().toList();

            List<FaceEntity> stillUnmatched = new ArrayList<>();
            for (FaceEntity face : ungroupedFaces) {
                Optional<PersonWithScore> bestMatch = findBestMatchForFace(face, persons);
                if (bestMatch.isPresent()) {
                    personRecognitionService.updatePerson(bestMatch.get().getPerson(), face);
                } else {
                    stillUnmatched.add(face);
                }
            }

            // Whatever didn't match an existing person gets clustered among itself into brand-new
            // people. Processing the FULL leftover set here (rather than a separately re-paginated
            // sample, as before) avoids splitting one real person's faces into multiple PersonEntity
            // rows purely because they landed in different pagination batches.
            clusterByTemporalProximity(stillUnmatched)
                    .forEach(cluster -> personRecognitionService.addPerson(user, cluster));
        } catch (Exception e) {
            log.error("cant process face recognition for user {}", user.getId(), e);
        }

    }

    private Optional<PersonWithScore> findBestMatchForFace(FaceEntity face, List<PersonEntity> persons) {
        // Find the person with the highest similarity score, using only each candidate's
        // temporally-nearest faces (see scoreAgainstPerson) — cheap enough to check every person.
        return persons.stream()
                .flatMap(person -> scoreAgainstPerson(face, person).stream())
                .max(Comparator.comparingDouble(PersonWithScore::getScore))
                .filter(match -> match.getScore() >= THRESHOLD);
    }

    private Optional<PersonWithScore> scoreAgainstPerson(FaceEntity face, PersonEntity person) {
        List<FaceEntity> neighbors = nearestTemporalNeighbors(person, face.getPhoto().getDateTaken());
        OptionalDouble averageSimilarity = calculateAverageSimilarity(face, neighbors);
        return averageSimilarity.isPresent()
                ? Optional.of(PersonWithScore.builder().person(person).score(averageSimilarity.getAsDouble()).build())
                : Optional.empty();
    }

    // The person's TEMPORAL_NEIGHBOR_WINDOW faces closest in time to targetDate, looking both
    // before and after it — a same-person match should be strongest against nearby-in-time
    // photos, not a photo from decades apart, so this keeps the comparison set coherent to one
    // era instead of averaging across the person's entire (possibly age-spanning) history.
    private List<FaceEntity> nearestTemporalNeighbors(PersonEntity person, LocalDateTime targetDate) {
        List<FaceEntity> before = faceRepository
                .findByPersonAndPhotoDateTakenBeforeOrderByPhotoDateTakenDesc(person, targetDate, Pageable.ofSize(TEMPORAL_NEIGHBOR_WINDOW))
                .stream().toList();
        List<FaceEntity> after = faceRepository
                .findByPersonAndPhotoDateTakenAfterOrderByPhotoDateTakenAsc(person, targetDate, Pageable.ofSize(TEMPORAL_NEIGHBOR_WINDOW))
                .stream().toList();

        return Stream.concat(before.stream(), after.stream())
                .sorted(Comparator.comparingLong(candidate ->
                        Math.abs(Duration.between(targetDate, candidate.getPhoto().getDateTaken()).toMillis())))
                .limit(TEMPORAL_NEIGHBOR_WINDOW)
                .toList();
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

    // Clusters faces with no existing-person match into brand-new people. Faces are sorted by
    // photo date, then each face is only compared against its next TEMPORAL_NEIGHBOR_WINDOW
    // faces in that chronological order — since the list is sorted, this single forward-looking
    // pass already covers every pair within the window in both directions (checking i->j also
    // covers j's "before" side), so it costs O(n * window) rather than the O(n^2) of comparing
    // every face to every other face.
    //
    // Matches are merged via union-find (connected components), not single-anchor grouping: a
    // direct link is only required between temporally-adjacent faces, and two faces far apart in
    // age can still end up in the same cluster by chaining through the faces in between — this is
    // what lets a childhood and an adult photo of the same person group together, as long as the
    // account has a reasonably continuous timeline of photos of them, without ever loosening the
    // strict pairwise THRESHOLD.
    private List<List<FaceEntity>> clusterByTemporalProximity(List<FaceEntity> facesToCluster) {
        if (CollectionUtils.isEmpty(facesToCluster)) {
            return new ArrayList<>();
        }

        List<FaceEntity> sorted = facesToCluster.stream()
                .filter(face -> face.getEmbedding() != null
                        && face.getPhoto() != null
                        && face.getPhoto().getDateTaken() != null)
                .sorted(Comparator.comparing(face -> face.getPhoto().getDateTaken()))
                .toList();

        int n = sorted.size();
        if (n == 0) {
            return new ArrayList<>();
        }

        float[][] embeddings = new float[n][];
        for (int i = 0; i < n; i++) {
            embeddings[i] = byteArrayToFloatArray(sorted.get(i).getEmbedding());
        }

        UnionFind unionFind = new UnionFind(n);
        for (int i = 0; i < n; i++) {
            int windowEnd = Math.min(n, i + 1 + TEMPORAL_NEIGHBOR_WINDOW);
            for (int j = i + 1; j < windowEnd; j++) {
                if (cosineSimilarity(embeddings[i], embeddings[j]) >= THRESHOLD) {
                    unionFind.union(i, j);
                }
            }
        }

        Map<Integer, List<FaceEntity>> componentsByRoot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            componentsByRoot.computeIfAbsent(unionFind.find(i), root -> new ArrayList<>()).add(sorted.get(i));
        }

        return componentsByRoot.values().stream()
                .filter(cluster -> cluster.size() >= MIN_NEW_PERSON_CLUSTER_SIZE)
                .toList();
    }

    // Minimal union-find (disjoint-set) with path compression + union by rank, used to merge
    // temporally-chained face matches into connected components.
    private static final class UnionFind {
        private final int[] parent;
        private final int[] rank;

        UnionFind(int size) {
            parent = new int[size];
            rank = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int i) {
            if (parent[i] != i) {
                parent[i] = find(parent[i]);
            }
            return parent[i];
        }

        void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA == rootB) {
                return;
            }
            if (rank[rootA] < rank[rootB]) {
                parent[rootA] = rootB;
            } else if (rank[rootA] > rank[rootB]) {
                parent[rootB] = rootA;
            } else {
                parent[rootB] = rootA;
                rank[rootA]++;
            }
        }
    }

    @Builder
    @Data
    private static class PersonWithScore {
        private PersonEntity person;
        private double score;
    }
}