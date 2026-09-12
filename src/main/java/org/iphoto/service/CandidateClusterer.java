package org.iphoto.service;

import org.iphoto.entities.FaceEntity;
import org.iphoto.util.EmbeddingCodec;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// Incremental, cross-run-persistable replacement for the original fully-in-memory,
// run-scoped design (see TODO.md's 2026-09-12 "face recognition algorithm rework" entry for the
// production complaint/rationale: every ~30s run used to rebuild its whole not-yet-a-person
// clustering state from scratch by re-feeding every still-unmatched face - potentially thousands,
// for as long as a real person's faces took to accumulate the 3 needed to promote - the exact
// "recursive calls to the same face again and again" cost this fixes).
//
// Still a plain, dependency-free class (no Spring, no JPA session/repository access - FaceEntity
// is used purely as an in-memory value here) so it's directly unit-testable without a database,
// unlike FaceRecognitionService itself. The persistence side (CandidateClusterEntity/
// FaceEntity.candidateCluster) lives entirely in the caller (FaceRecognitionServiceImpl):
// - at the start of a run, load every still-open CandidateClusterEntity for the space and its
//   member faces (FaceRepository.findByCandidateClusterIdFetchPhoto), and seed() this class with
//   each one - these faces are NEVER refetched/reprocessed themselves again, only used to match
//   against.
// - feed ONLY genuinely new (FaceStatus.NEW) faces to add() - a face already examined once,
//   successful match or not, is flipped to OLD and its outcome (person, or which cluster it
//   joined/seeded) is what's now remembered, not the face itself being re-run through matching.
// - on a non-promoting add() result, persist the returned clusterId as that face's
//   candidateCluster (creating a new CandidateClusterEntity row first if isNewCluster()).
// - on a promoting result, persist the new person as before (PersonRecognitionService#addPerson)
//   and delete the now-resolved CandidateClusterEntity row.
//
// idGenerator exists so add() can hand back a real, usable id for a brand-new cluster WITHOUT this
// class touching a database itself - the caller persists a CandidateClusterEntity using that exact
// id (Hibernate's GenerationType.UUID generator accepts a pre-assigned id, same pattern already
// used elsewhere in this codebase for client-generated UUID primary keys), keeping in-memory and
// persisted state trivially consistent with no round-trip needed before add() can return.
//
// Faces MUST be fed to add() in chronological photo.dateTaken-ascending order within a run (not
// just within one slice) - see FaceRepository.findNewBySpaceIdFetchPhoto's ORDER BY and
// FaceRecognitionServiceImpl.groupFacesForUser, which feeds one long-lived instance of this class
// across every slice of one user's run. Chronological order is what lets a cluster's up-to-3 held
// representatives get picked up as the timeline progresses (a childhood photo, then a teenage one,
// then an adult one) instead of whichever 3 happened to come back first, narrowing (not
// eliminating) the transitive-chaining loss inherent to comparing only against a capped
// representative set rather than a full connected component. A seeded cluster's own representative
// order (from whichever run(s) actually added them) is preserved as-is - only newly-added faces
// within THIS run need to arrive in order relative to each other and to any seeded cluster's
// existing members' own dates, which the maxGap check on every comparison already enforces
// regardless of arrival order.
//
// A CandidateCluster (see the inner class below) never holds more than MAX_REPRESENTATIVE_FACES
// (3) FaceEntity references, no matter how many faces eventually belong to the real person it
// represents - this is the actual memory fix from the original design this class replaced: a
// cluster promotes (and is discarded from this class's own open-cluster list) the INSTANT its
// member count reaches 3, using exactly those 3 faces as the promotion payload.
public class CandidateClusterer {

    private final double similarityThreshold;
    private final Duration maxGap;
    private final int maxRepresentativeFaces;
    private final Supplier<UUID> idGenerator;
    private final List<CandidateCluster> openClusters = new ArrayList<>();

    public CandidateClusterer(double similarityThreshold, Duration maxGap, int maxRepresentativeFaces) {
        this(similarityThreshold, maxGap, maxRepresentativeFaces, UUID::randomUUID);
    }

    public CandidateClusterer(double similarityThreshold, Duration maxGap, int maxRepresentativeFaces, Supplier<UUID> idGenerator) {
        this.similarityThreshold = similarityThreshold;
        this.maxGap = maxGap;
        this.maxRepresentativeFaces = maxRepresentativeFaces;
        this.idGenerator = idGenerator;
    }

    /**
     * Pre-populates one already-open cluster from a prior run (see class comment) - must be called
     * before any add() calls for this instance, once per persisted CandidateClusterEntity, with
     * its current member faces (never empty/more than maxRepresentativeFaces - 1, since a cluster
     * that already reached maxRepresentativeFaces would have been promoted and deleted already).
     */
    public void seed(UUID clusterId, List<FaceEntity> existingMembers) {
        openClusters.add(new CandidateCluster(clusterId, existingMembers));
    }

    /**
     * Feeds one face through the candidate-cluster matching/promotion logic: it joins the first
     * open cluster it matches (cosine similarity >= similarityThreshold against, and within maxGap
     * of, at least one of that cluster's held representatives), or seeds a brand-new one-member
     * cluster if it matches none. See ClusterAddResult for what the caller must persist for each
     * outcome.
     */
    public ClusterAddResult add(FaceEntity face) {
        for (int i = 0; i < openClusters.size(); i++) {
            CandidateCluster cluster = openClusters.get(i);
            if (cluster.matches(face, similarityThreshold, maxGap)) {
                cluster.representatives.add(face);
                if (cluster.representatives.size() >= maxRepresentativeFaces) {
                    openClusters.remove(i);
                    return ClusterAddResult.promoted(cluster.id, cluster.representatives);
                }
                return ClusterAddResult.joinedExisting(cluster.id);
            }
        }
        UUID newId = idGenerator.get();
        openClusters.add(new CandidateCluster(newId, face));
        return ClusterAddResult.newCluster(newId);
    }

    /**
     * @param clusterId               always set - the cluster this face ended up part of, whether
     *                                brand-new, an existing seeded/in-run one, or (on promotion)
     *                                the one that just got resolved into a real person and should
     *                                be deleted.
     * @param isNewCluster            true only when clusterId wasn't seen before this call - the
     *                                caller must persist a new CandidateClusterEntity using exactly
     *                                this id before saving the face's candidateCluster reference.
     * @param promotedRepresentatives non-null only when this call completed a cluster - exactly
     *                                the faces to hand to PersonRecognitionService#addPerson; the
     *                                caller should then delete the CandidateClusterEntity row.
     */
    public record ClusterAddResult(UUID clusterId, boolean isNewCluster, List<FaceEntity> promotedRepresentatives) {
        static ClusterAddResult newCluster(UUID id) {
            return new ClusterAddResult(id, true, null);
        }

        static ClusterAddResult joinedExisting(UUID id) {
            return new ClusterAddResult(id, false, null);
        }

        static ClusterAddResult promoted(UUID id, List<FaceEntity> representatives) {
            return new ClusterAddResult(id, false, representatives);
        }

        public boolean isPromotion() {
            return promotedRepresentatives != null;
        }
    }

    // Deliberately no smarter "most diverse era" representative selection here: just the first
    // faces (in arrival order, across however many runs it took) that joined the cluster, up to
    // maxRepresentativeFaces - a possible future refinement if chronological feed order alone
    // doesn't prove sufficient in practice (see TODO.md's "Open questions - RESOLVED" #2).
    private static final class CandidateCluster {
        private final UUID id;
        private final List<FaceEntity> representatives;

        CandidateCluster(UUID id, FaceEntity first) {
            this.id = id;
            this.representatives = new ArrayList<>();
            representatives.add(first);
        }

        CandidateCluster(UUID id, List<FaceEntity> existingMembers) {
            this.id = id;
            this.representatives = new ArrayList<>(existingMembers);
        }

        // A direct pairwise check against each held representative - not a weighted-average bucket
        // comparison like the existing-person path, since these are raw candidate faces, not yet a
        // real person's derived bucket. Matches on the FIRST representative satisfying both the
        // similarity and temporal bound, per this cluster's arrival-ordered representative list.
        boolean matches(FaceEntity face, double threshold, Duration maxGap) {
            float[] faceEmbedding = EmbeddingCodec.toFloats(face.getEmbedding());
            LocalDateTime faceDate = photoDateTaken(face);
            for (FaceEntity representative : representatives) {
                if (withinGap(faceDate, photoDateTaken(representative), maxGap)
                        && cosineSimilarity(faceEmbedding, EmbeddingCodec.toFloats(representative.getEmbedding())) >= threshold) {
                    return true;
                }
            }
            return false;
        }
    }

    private static LocalDateTime photoDateTaken(FaceEntity face) {
        return face.getPhoto() != null ? face.getPhoto().getDateTaken() : null;
    }

    // Missing dateTaken on either side is treated as "no temporal info to bound against" rather
    // than throwing - defensive, since nothing elsewhere in this codebase guarantees dateTaken is
    // always populated (see PersonEmbeddingBucketEntity.bucketYearFor's own similar fallback).
    private static boolean withinGap(LocalDateTime a, LocalDateTime b, Duration maxGap) {
        if (a == null || b == null) {
            return false;
        }
        return Duration.between(a, b).abs().compareTo(maxGap) <= 0;
    }

    // Same cosine similarity math as FaceRecognitionServiceImpl's own (private, unshared) helper -
    // duplicated rather than extracted into a shared utility, since this class is deliberately kept
    // standalone/dependency-free (see this file's own header comment) and FaceRecognitionServiceImpl
    // still needs its own copy for existing-person bucket comparisons (a different vector shape/
    // weighting, not a straight pairwise call).
    private static double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension.");
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += (double) vectorA[i] * vectorA[i];
            normB += (double) vectorB[i] * vectorB[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
