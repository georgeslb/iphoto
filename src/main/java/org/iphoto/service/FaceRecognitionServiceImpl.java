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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    // lets matches chain through nearby ages, so long-range drift is handled by transitivity (see
    // CandidateClusterer's own class comment on chronological feed order), not by weakening the
    // per-pair confidence bar.
    private static final double THRESHOLD = 0.60;

    // Cap on ungrouped faces processed in a single run. groupFaces() reruns every ~30s
    // (TaskWorkerManager's FACE_RECOGNITION task), so a backlog beyond this is simply picked up
    // by later runs — this just bounds memory/cost per run rather than being a correctness limit.
    private static final int MAX_UNGROUPED_FACES_PER_RUN = 2000;

    // How many ungrouped faces are fetched (and held in memory) at a time, out of the run's
    // overall MAX_UNGROUPED_FACES_PER_RUN ceiling - replaces the old single 2000-row fetch. This,
    // together with CandidateClusterer's own ≤3-representative-per-cluster cap, is what actually
    // bounds this method's peak memory footprint: a slice's own FaceEntity/PhotoEntity objects
    // become eligible for GC once it finishes, leaving only whatever CandidateClusterer itself
    // still holds (≤3 faces per still-open cluster) referenced across slices. See TODO.md's
    // "qualif-server item 5" entry for the full worked-example rationale.
    private static final int CLUSTERING_SLICE_SIZE = 200;

    // Minimum faces required for a brand-new (not-yet-matched-to-an-existing-person) cluster to
    // become a PersonEntity, to avoid spurious 1-2 face "people" from noise/coincidental matches.
    // Reused directly as CandidateClusterer's own representative cap/promotion threshold below - a
    // cluster promotes the INSTANT it reaches this many members, using exactly those faces as both
    // its representatives and the personRecognitionService.addPerson payload.
    private static final int MIN_NEW_PERSON_CLUSTER_SIZE = 3;

    // Ceiling on how far apart (in real elapsed time) two faces can be and still be compared
    // during new-person clustering (see CandidateClusterer). Widened from the original 24h to 90
    // days - see TODO.md's "qualif-server item 5" entry, resolved question 1: 24h was causing a
    // real "too few matches" problem, and the old position-based cost concern that originally
    // motivated a tight window no longer applies now that comparisons are bounded by
    // CandidateClusterer's own ≤3-representative cap rather than by scanning a growing candidate
    // window.
    private static final Duration MAX_NEW_PERSON_CLUSTER_GAP = Duration.ofDays(90);

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
            // Resolved once, explicitly, and threaded through every query below by id - NOT via
            // the @MainSpaceLimited-annotated repository methods this used to call
            // (personRepository.findAll()/faceRepository.findByPersonIsNullFetchPhoto). Those
            // silently do nothing on the thread this runs on: TaskWorkerManager's dedicated
            // heavy-task pool matches MainSpaceAspectConfiguration's own backend-task*/heavy-task*
            // bypass, so the filter never actually got bound to any space there - meaning every
            // user's people/faces were being pooled together globally, and a real cluster could
            // end up mixing faces that belong to completely different accounts into one person.
            // Passing the space explicitly (findAllBySpaceId/findByPersonIsNullAndSpaceIdFetchPhoto)
            // is correct regardless of which thread this runs on.
            SpaceEntity space = spaceService.getDefaultSpaceForUser(user);
            // Still snapshotted once per run, same as before this rework - but now MUTATED as
            // brand-new people are promoted mid-run (see below), so later slices (and later faces
            // within the same slice) see them via the normal existing-person path instead of
            // risking a second, duplicate CandidateCluster/person for the same subject.
            List<PersonEntity> persons = new ArrayList<>(personRepository.findAllBySpaceId(space.getId()));
            Map<UUID, List<PersonEmbeddingBucketEntity>> bucketsByPerson = new HashMap<>(personEmbeddingBucketRepository
                    .findByPersonIdIn(persons.stream().map(PersonEntity::getId).toList())
                    .stream()
                    .collect(Collectors.groupingBy(bucket -> bucket.getPerson().getId())));

            // One instance per user run, fed faces across every slice below in chronological
            // (photo.dateTaken ascending) order - see CandidateClusterer's own class comment for
            // why that ordering matters and how its ≤3-representative cap bounds memory regardless
            // of how large the real person behind any one cluster turns out to be.
            CandidateClusterer candidateClusterer = new CandidateClusterer(THRESHOLD, MAX_NEW_PERSON_CLUSTER_GAP, MIN_NEW_PERSON_CLUSTER_SIZE);

            int processedTotal = 0;
            int pageNumber = 0;
            while (processedTotal < MAX_UNGROUPED_FACES_PER_RUN) {
                // FetchPhoto: this list outlives the short transaction that loaded it (see class
                // comment - each face's own match/cluster write is now its own separate
                // transaction), and both the logging line below and CandidateClusterer read
                // face.getPhoto() well after that point - needs to already be loaded, not a lazy
                // proxy with no session left to resolve it on demand.
                Slice<FaceEntity> slice = faceRepository.findByPersonIsNullAndSpaceIdFetchPhoto(space.getId(), PageRequest.of(pageNumber, CLUSTERING_SLICE_SIZE));
                List<FaceEntity> faces = slice.getContent();
                if (faces.isEmpty()) {
                    break;
                }

                // A face manually unlinked from a person (FaceService.unlinkFaceFromPerson /
                // setFacePerson) must never be silently reattached to that SAME person by this
                // automatic pass — that would undo the user's correction. It can still match a
                // different person, or seed a brand-new one below. Loaded fresh per slice, keyed by
                // face id, instead of a query per (face, candidate person) pair.
                Map<UUID, Set<UUID>> excludedPersonIdsByFace = faceLinkExclusionRepository
                        .findByFaceIdIn(faces.stream().map(FaceEntity::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                exclusion -> exclusion.getFace().getId(),
                                Collectors.mapping(exclusion -> exclusion.getExcludedPerson().getId(), Collectors.toSet())
                        ));

                for (FaceEntity face : faces) {
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
                        continue;
                    }

                    // No existing person matched - hand it to the memory-bounded candidate
                    // clusterer instead of accumulating it into a full-run leftover list.
                    Optional<List<FaceEntity>> promotedClusterFaces = candidateClusterer.add(face);
                    if (promotedClusterFaces.isEmpty()) {
                        continue;
                    }
                    // Same reasoning as the per-face try/catch above - one bad cluster (each its
                    // own independent transaction) shouldn't stop the rest of this user's new-person
                    // clusters from being created in this run.
                    try {
                        personRecognitionService.addPerson(promotedClusterFaces.get());
                        // Mid-run visibility (see class-level field comment above): re-fetch the
                        // brand-new person via one of the faces just handed to addPerson and fold
                        // it into this run's own persons/bucketsByPerson, so every
                        // later face - in this same slice or a later one - matches it via the
                        // normal existing-person path instead of seeding a duplicate cluster.
                        UUID newPersonId = faceRepository.findById(promotedClusterFaces.get().getFirst().getId())
                                .orElseThrow().getPerson().getId();
                        personRepository.findById(newPersonId).ifPresent(persons::add);
                        bucketsByPerson.put(newPersonId, personEmbeddingBucketRepository.findByPersonId(newPersonId));
                    } catch (Exception e) {
                        log.error("could not create a new person for user {} from a cluster of {} face(s)",
                                user.getId(), promotedClusterFaces.get().size(), e);
                    }
                }

                processedTotal += faces.size();
                if (!slice.hasNext()) {
                    break;
                }
                pageNumber++;
            }
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
            float[] bucketVector = EmbeddingCodec.toFloats(bucket.getEmbedding());
            // A dimension mismatch means this bucket can't be meaningfully compared at all (most
            // likely a different embedding-model generation coexisting from before a model swap -
            // `persons` is properly space-scoped now, see groupFacesForUser's own comment, so this
            // is no longer expected to fire on some OTHER user's person the way it could when
            // @MainSpaceLimited was silently a no-op here). Previously this threw out of
            // cosineSimilarity uncaught, aborting groupFacesForUser's ENTIRE run
            // for whichever user happened to be current at that moment (every remaining face and
            // candidate cluster in that run silently abandoned, caught only by the method's own
            // outer catch-all) - one incompatible bucket anywhere in `persons` was enough to break
            // face grouping for everyone. Skipping just this one bucket and continuing is exactly
            // what an empty/no-match result already means for a person with no comparable buckets
            // at all (see the totalWeight == 0 check below).
            if (bucketVector.length != faceEmbedding.vector().length) {
                log.warn("Skipping person {} bucket {} for embedding comparison - dimension mismatch ({} vs {})",
                        person.getId(), bucket.getId(), bucketVector.length, faceEmbedding.vector().length);
                continue;
            }
            double similarity = cosineSimilarity(faceEmbedding, bucketVector);
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

    @Builder
    @Data
    private static class PersonWithScore {
        private PersonEntity person;
        private double score;
    }
}
