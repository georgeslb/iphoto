package org.iphoto.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.repository.FaceLinkExclusionRepository;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonEmbeddingBucketRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.util.EmbeddingCodec;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// Deliberately NOT class-level @Transactional (unlike most services here) - see groupFaces' own
// comment for why: wrapping this whole method (every non-admin user, up to
// MAX_UNGROUPED_FACES_PER_RUN faces each) in one transaction meant one huge, long-held DB
// transaction per run - a lock/connection held for however long the whole thing took, and a
// failure anywhere rolling back every user (even ones already fully, correctly processed earlier
// in the same run). Every actual write below now goes through personRecognitionService, whose own
// methods are plain @Transactional (REQUIRED) - with no ambient transaction here to join, each one
// simply starts and commits its own, small and independent: one per matched face, one per
// brand-new person cluster, one per user's worth of work overall. See PersonRecognitionServiceImpl.
// updatePerson's own comment for the other half of this (re-fetching fresh, attached entities
// rather than trusting whatever's passed in, since callers here now hand it detached ones).
@Service
@Slf4j
@RequiredArgsConstructor
public class FaceRecognitionServiceImpl implements FaceRecognitionService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final PersonRecognitionService personRecognitionService;
    private final UserRepository userRepository;
    private final SpaceService spaceService;
    private final FaceLinkExclusionRepository faceLinkExclusionRepository;
    private final PersonEmbeddingBucketRepository personEmbeddingBucketRepository;

    // Threshold for Cosine Similarity: A higher value means a stricter match. Kept strict and
    // never loosened for "same person, different age" cases — a face at 5 and the same face at
    // 25 are NOT expected to clear this directly. Instead, a rich timeline of in-between photos
    // lets matches chain through nearby ages (see TEMPORAL_NEIGHBOR_WINDOW below), so long-range
    // drift is handled by transitivity, not by weakening the per-pair confidence bar.
    private static final double THRESHOLD = 0.60;

    // No longer used for matching against EXISTING persons (see PersonEmbeddingBucketEntity/
    // NEAREST_BUCKET_COUNT below for that, now precomputed per-year buckets instead of live
    // per-face queries) - kept only as the base unit for clusterByTemporalProximity's
    // maxCandidatesPerFace safety cap, for NEW-person clustering.
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

    @Override
    public void groupFaces() { // Added UserEntity parameter
        // Ids only, not the entities themselves - findById below re-fetches each fresh right
        // before it's needed rather than holding a big pre-fetched list of UserEntity around for
        // the whole loop (harmless either way now that nothing here shares one long transaction/
        // session across users, but keeps this loop's own memory footprint minimal too).
        //
        // No transaction wraps this loop (see class comment) - groupFacesForUser below reads
        // (persons/ungrouped faces/buckets) each get their own short, Spring-Data-managed
        // transaction, and every actual write goes through personRecognitionService, whose plain
        // @Transactional methods each start and commit independently with no ambient transaction
        // here to join. So a failure partway through one user - or even partway through one user's
        // own faces - can no longer roll back anything already committed before it, for this user
        // or any other; previously it was one all-or-nothing transaction for the entire run.
        List<UUID> nonAdminUserIds = userRepository.findAll().stream()
                .filter(userEntity -> !userEntity.getRole().equals("ADMIN"))
                .map(UserEntity::getId)
                .toList();
        for (UUID userId : nonAdminUserIds) {
            userRepository.findById(userId).ifPresent(this::groupFacesForUser);
        }
    }

    private void groupFacesForUser(UserEntity user) {
        try {
            log.info("starting person grouping for user {}", user.getUsername());
            List<PersonEntity> persons = personRepository.findAll();
            // FetchPhoto: this list outlives the short transaction that loaded it (see class
            // comment - each face's own match/cluster write is now its own separate transaction),
            // and both the logging line below and clusterByTemporalProximity read face.getPhoto()
            // well after that point - needs to already be loaded, not a lazy proxy with no session
            // left to resolve it on demand.
            List<FaceEntity> ungroupedFaces = faceRepository
                    .findByPersonIsNullFetchPhoto(Pageable.ofSize(MAX_UNGROUPED_FACES_PER_RUN))
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

            // Precomputed per-person, per-year embedding buckets (see PersonEmbeddingBucketEntity),
            // batch-loaded once for every candidate person in this whole run - matching a face
            // against a person is now a pure in-memory comparison against this small precomputed
            // set instead of a fresh DB query (nearestTemporalNeighbors, since removed) per
            // (face, person) pair.
            Map<UUID, List<PersonEmbeddingBucketEntity>> bucketsByPerson = personEmbeddingBucketRepository
                    .findByPersonIdIn(persons.stream().map(PersonEntity::getId).toList())
                    .stream()
                    .collect(Collectors.groupingBy(bucket -> bucket.getPerson().getId()));

            List<FaceEntity> stillUnmatched = new ArrayList<>();
            for (FaceEntity face : ungroupedFaces) {
                log.info("Working on face in photo {}", face.getPhoto().getId());
                Set<UUID> excludedPersonIds = excludedPersonIdsByFace.getOrDefault(face.getId(), Set.of());
                List<PersonEntity> eligiblePersons = excludedPersonIds.isEmpty()
                        ? persons
                        : persons.stream().filter(person -> !excludedPersonIds.contains(person.getId())).toList();

                // Decoded/normalized once per face rather than once per (face, person) pair below -
                // findBestMatchForFace checks every eligible person, and each check used to re-decode
                // and re-normalize this SAME face's embedding from scratch.
                NormalizedEmbedding faceEmbedding = NormalizedEmbedding.of(EmbeddingCodec.toFloats(face.getEmbedding()));
                int targetYear = PersonEmbeddingBucketEntity.bucketYearFor(face);
                Optional<PersonWithScore> bestMatch = findBestMatchForFace(faceEmbedding, targetYear, eligiblePersons, bucketsByPerson);
                if (bestMatch.isPresent()) {
                    // Each match is now its own independent transaction (see class comment) - a
                    // failure on one face must not stop the rest of this user's faces (or the
                    // new-person clustering below) from being attempted in this same run, the way
                    // it used to when this whole method shared one transaction and one uncaught
                    // exception aborted everything after it.
                    try {
                        personRecognitionService.updatePerson(bestMatch.get().getPerson(), face);
                    } catch (Exception e) {
                        log.error("could not update person {} for face {}", bestMatch.get().getPerson().getId(), face.getId(), e);
                    }
                } else {
                    stillUnmatched.add(face);
                }
            }

            // Whatever didn't match an existing person gets clustered among itself into brand-new
            // people. Processing the FULL leftover set here (rather than a separately re-paginated
            // sample, as before) avoids splitting one real person's faces into multiple PersonEntity
            // rows purely because they landed in different pagination batches.
            clusterByTemporalProximity(stillUnmatched).forEach(cluster -> {
                // Same reasoning as the per-face try/catch above - one bad cluster (each its own
                // independent transaction) shouldn't stop the rest of this user's new-person
                // clusters from being created in this run.
                try {
                    personRecognitionService.addPerson(user, cluster);
                } catch (Exception e) {
                    log.error("could not create a new person for user {} from a cluster of {} face(s)", user.getId(), cluster.size(), e);
                }
            });
            log.info("finishing person grouping for user {}", user.getUsername());
        } catch (Exception e) {
            log.error("cant process face recognition for user {}", user.getId(), e);
        }

    }

    private Optional<PersonWithScore> findBestMatchForFace(NormalizedEmbedding faceEmbedding, int targetYear, List<PersonEntity> persons,
                                                            Map<UUID, List<PersonEmbeddingBucketEntity>> bucketsByPerson) {
        // Find the person with the highest similarity score, using only each candidate's
        // temporally-nearest buckets (see scoreAgainstPerson) — cheap enough to check every person.
        return persons.stream()
                .flatMap(person -> scoreAgainstPerson(faceEmbedding, targetYear, person, bucketsByPerson.getOrDefault(person.getId(), List.of())).stream())
                .max(Comparator.comparingDouble(PersonWithScore::getScore))
                .filter(match -> match.getScore() >= THRESHOLD);
    }

    // How many of a person's nearest-year embedding buckets are considered per comparison - the
    // bucket-based equivalent of the old before/after face-level TEMPORAL_NEIGHBOR_WINDOW, just at
    // year granularity: 2 lets a face right at a year boundary still weigh in the adjacent year's
    // bucket rather than only ever the exact same year's.
    private static final int NEAREST_BUCKET_COUNT = 2;

    // Compares against the person's precomputed per-year embedding buckets (see
    // PersonEmbeddingBucketEntity) nearest to the face's own year, weighted by how many faces are
    // actually behind each bucket so a thin bucket can't outweigh a well-populated one. No DB
    // query here at all - bucketsByPerson was already batch-loaded once for the whole run.
    private Optional<PersonWithScore> scoreAgainstPerson(NormalizedEmbedding faceEmbedding, int targetYear, PersonEntity person,
                                                          List<PersonEmbeddingBucketEntity> personBuckets) {
        if (personBuckets.isEmpty()) {
            return Optional.empty();
        }
        List<PersonEmbeddingBucketEntity> nearest = personBuckets.stream()
                .sorted(Comparator.comparingInt(bucket -> Math.abs(bucket.getBucketYear() - targetYear)))
                .limit(NEAREST_BUCKET_COUNT)
                .toList();

        double weightedSimilaritySum = 0;
        int totalWeight = 0;
        for (PersonEmbeddingBucketEntity bucket : nearest) {
            double similarity = cosineSimilarity(faceEmbedding, EmbeddingCodec.toFloats(bucket.getEmbedding()));
            weightedSimilaritySum += similarity * bucket.getFaceCount();
            totalWeight += bucket.getFaceCount();
        }
        if (totalWeight == 0) {
            return Optional.empty();
        }
        return Optional.of(PersonWithScore.builder().person(person).score(weightedSimilaritySum / totalWeight).build());
    }

    // A decoded embedding with its vector norm precomputed once - scoreAgainstPerson above is
    // called once per (face, person) pair, and re-decoding + re-normalizing the SAME face's
    // embedding from scratch for every candidate person (up to persons.size() times per face) was
    // pure wasted CPU work; this vector never changes across those calls within one groupFaces()
    // run, only the comparison side (the candidate person's buckets) does.
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
            embeddings[i] = EmbeddingCodec.toFloats(sorted.get(i).getEmbedding());
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
