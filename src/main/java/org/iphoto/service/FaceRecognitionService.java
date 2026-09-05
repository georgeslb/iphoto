package org.iphoto.service;

import jakarta.persistence.EntityManager;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.repository.FaceLinkExclusionRepository;
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
import java.util.stream.Collectors;
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
    private final FaceLinkExclusionRepository faceLinkExclusionRepository;

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

    // Ceiling on how far apart (in real elapsed time) two faces can be and still be compared
    // during new-person clustering (see clusterByTemporalProximity). This is the primary bound
    // there now — TEMPORAL_NEIGHBOR_WINDOW is kept only as a cost safety cap, see that method's
    // comment for why a pure position-based window was wrong.
    private static final Duration MAX_NEW_PERSON_CLUSTER_GAP = Duration.ofHours(24);
    private final EntityManager entityManager;

    // No longer self-locking - FACE_RECOGNITION now runs on its own dedicated single-thread pool
    // (see TaskWorkerManager/IPhotoProperties.HeavyTasks#faceRecognitionThreads), which already
    // guarantees only one call runs at a time.
    public void groupFaces() { // Added UserEntity parameter
        userRepository.findAll().stream().filter(userEntity -> !userEntity.getRole().equals("ADMIN")).forEach(user -> {
            groupFacesForUser(user);
            entityManager.flush();
        });
    }

    private void groupFacesForUser(UserEntity user) {
        try {
            log.info("starting person grouping for user {}", user.getUsername());
            List<PersonEntity> persons = personRepository.findAll();
            List<FaceEntity> ungroupedFaces = faceRepository
                    .findByPersonIsNull(Pageable.ofSize(MAX_UNGROUPED_FACES_PER_RUN))
                    .stream().toList();

            // A face manually unlinked from a person (FaceService.unlinkFaceFromPerson /
            // setFacePerson) must never be silently reattached to that SAME person by this
            // automatic pass — that would undo the user's correction. It can still match a
            // different person, or seed a brand-new one below. Loaded once per run, keyed by
            // face id, instead of a query per (face, candidate person) pair.
            Map<UUID, Set<UUID>> excludedPersonIdsByFace = faceLinkExclusionRepository
                    .findByFaceIdIn(ungroupedFaces.stream().map(FaceEntity::getId).toList())
                    .stream()
                    .collect(Collectors.groupingBy(
                            exclusion -> exclusion.getFace().getId(),
                            Collectors.mapping(exclusion -> exclusion.getExcludedPerson().getId(), Collectors.toSet())
                    ));

            List<FaceEntity> stillUnmatched = new ArrayList<>();
            for (FaceEntity face : ungroupedFaces) {
                Set<UUID> excludedPersonIds = excludedPersonIdsByFace.getOrDefault(face.getId(), Set.of());
                List<PersonEntity> eligiblePersons = excludedPersonIds.isEmpty()
                        ? persons
                        : persons.stream().filter(person -> !excludedPersonIds.contains(person.getId())).toList();

                // Decoded/normalized once per face rather than once per (face, person) pair below -
                // findBestMatchForFace checks every eligible person, and each check used to re-decode
                // and re-normalize this SAME face's embedding from scratch, up to
                // persons.size() * TEMPORAL_NEIGHBOR_WINDOW times over for one face.
                NormalizedEmbedding faceEmbedding = NormalizedEmbedding.of(byteArrayToFloatArray(face.getEmbedding()));
                Optional<PersonWithScore> bestMatch = findBestMatchForFace(face, faceEmbedding, eligiblePersons);
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
            log.info("finishing person grouping for user {}", user.getUsername());
        } catch (Exception e) {
            log.error("cant process face recognition for user {}", user.getId(), e);
        }

    }

    private Optional<PersonWithScore> findBestMatchForFace(FaceEntity face, NormalizedEmbedding faceEmbedding, List<PersonEntity> persons) {
        // Find the person with the highest similarity score, using only each candidate's
        // temporally-nearest faces (see scoreAgainstPerson) — cheap enough to check every person.
        return persons.stream()
                .flatMap(person -> scoreAgainstPerson(face, faceEmbedding, person).stream())
                .max(Comparator.comparingDouble(PersonWithScore::getScore))
                .filter(match -> match.getScore() >= THRESHOLD);
    }

    private Optional<PersonWithScore> scoreAgainstPerson(FaceEntity face, NormalizedEmbedding faceEmbedding, PersonEntity person) {
        List<FaceEntity> neighbors = nearestTemporalNeighbors(person, face.getPhoto().getDateTaken());
        OptionalDouble averageSimilarity = calculateAverageSimilarity(faceEmbedding, neighbors);
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

    private OptionalDouble calculateAverageSimilarity(NormalizedEmbedding faceEmbedding, List<FaceEntity> personFaces) {
        if (personFaces.isEmpty()) {
            return OptionalDouble.empty();
        }
        return personFaces.stream()
                .map(FaceEntity::getEmbedding)
                .map(this::byteArrayToFloatArray)
                .mapToDouble(knownEmbeddingFloats -> this.cosineSimilarity(faceEmbedding, knownEmbeddingFloats))
                .average();
    }

    // A decoded embedding with its vector norm precomputed once - calculateAverageSimilarity above
    // is called once per (face, person) pair, and re-decoding + re-normalizing the SAME face's
    // embedding from scratch for every candidate person (up to persons.size() times per face) was
    // pure wasted CPU work; this vector never changes across those calls within one groupFaces()
    // run, only the comparison side (personFaces) does.
    private record NormalizedEmbedding(float[] vector, double norm) {
        static NormalizedEmbedding of(float[] vector) {
            double sumSquares = 0.0;
            for (float v : vector) {
                sumSquares += (double) v * v;
            }
            return new NormalizedEmbedding(vector, Math.sqrt(sumSquares));
        }
    }

    /**
     * Cosine similarity against a pre-normalized vector (see NormalizedEmbedding) - avoids
     * recomputing that side's norm on every call.
     */
    private double cosineSimilarity(NormalizedEmbedding a, float[] vectorB) {
        if (a.vector().length != vectorB.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension.");
        }
        double dotProduct = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorB.length; i++) {
            dotProduct += a.vector()[i] * vectorB[i];
            normB += (double) vectorB[i] * vectorB[i];
        }
        if (a.norm() == 0 || normB == 0) {
            return 0; // Avoid division by zero
        }
        return dotProduct / (a.norm() * Math.sqrt(normB));
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
            // x*x rather than Math.pow(x, 2) - a plain multiply, no transcendental-function
            // overhead for what's always an integer square here.
            normA += (double) vectorA[i] * vectorA[i];
            normB += (double) vectorB[i] * vectorB[i];
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
    // photo date, then each face is compared against the faces that follow it, bounded primarily
    // by MAX_NEW_PERSON_CLUSTER_GAP (real elapsed time) rather than by position in the sorted
    // list. Since the list is sorted, this single forward-looking pass already covers every pair
    // within the gap in both directions (checking i->j also covers j's "before" side).
    //
    // BUG THIS FIXES: the window used to be purely positional (next TEMPORAL_NEIGHBOR_WINDOW
    // faces, full stop) over the list of ALL of the user's ungrouped faces across every photo —
    // not scoped to one event/session. Two people recurring ~40 minutes apart across 3 photos of
    // the same gathering went completely ungrouped because ~13 unrelated faces from OTHER
    // people's photos, taken in between, pushed the second occurrence outside the position
    // window even though it was nowhere near TEMPORAL_NEIGHBOR_WINDOW faces away in *time*. Each
    // real person then fragmented into disconnected 2-face (or 1-face) components, all of which
    // MIN_NEW_PERSON_CLUSTER_SIZE silently discarded. A generous position-based cap
    // (maxCandidatesPerFace, well above TEMPORAL_NEIGHBOR_WINDOW) is kept below purely as a cost
    // safety net, so a burst of many photos within the same short time span doesn't blow up cost.
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

        // Hard cap on candidates compared per face, on top of the time gap below — a dense event
        // (hundreds/thousands of same-day photos) could otherwise turn the time-bounded scan into
        // real O(n^2) cost. TEMPORAL_NEIGHBOR_WINDOW faces would have been too tight (that's the
        // bug being fixed here), so this is deliberately a much larger multiple of it, not the
        // same value.
        int maxCandidatesPerFace = TEMPORAL_NEIGHBOR_WINDOW * 20;

        UnionFind unionFind = new UnionFind(n);
        for (int i = 0; i < n; i++) {
            LocalDateTime baseTime = sorted.get(i).getPhoto().getDateTaken();
            int candidateCap = Math.min(n, i + 1 + maxCandidatesPerFace);
            for (int j = i + 1; j < candidateCap; j++) {
                // List is sorted by dateTaken, so once one candidate exceeds the gap every later
                // one does too — safe to stop scanning this i entirely, not just skip this j.
                if (Duration.between(baseTime, sorted.get(j).getPhoto().getDateTaken())
                        .compareTo(MAX_NEW_PERSON_CLUSTER_GAP) > 0) {
                    break;
                }
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