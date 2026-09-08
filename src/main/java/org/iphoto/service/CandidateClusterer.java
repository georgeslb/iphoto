package org.iphoto.service;

import org.iphoto.entities.FaceEntity;
import org.iphoto.util.EmbeddingCodec;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Memory-bounded replacement for FaceRecognitionServiceImpl's old full union-find
// clusterByTemporalProximity - see TODO.md's "Design confirmed, not yet implemented - qualif-server
// item 5" entry for the full design/rationale. A plain, dependency-free class (no Spring, no JPA
// session/repository access - FaceEntity is used purely as an in-memory value here) so it's
// directly unit-testable without a database, unlike FaceRecognitionService itself, which is
// excluded from this project's per-interface integration-test suite.
//
// Faces MUST be fed to add() in chronological photo.dateTaken-ascending order across the WHOLE
// run (not just within one slice) - see FaceRepository.findByPersonIsNullAndSpaceIdFetchPhoto's ORDER BY and
// FaceRecognitionServiceImpl.groupFacesForUser, which feeds one long-lived instance of this class
// across every slice of one user's run. Chronological order is what lets a cluster's up-to-3 held
// representatives get picked up as the timeline progresses (a childhood photo, then a teenage one,
// then an adult one) instead of whichever 3 happened to come back first, narrowing (not
// eliminating) the transitive-chaining loss inherent to comparing only against a capped
// representative set rather than a full connected component.
//
// A CandidateCluster (see the inner class below) never holds more than MAX_REPRESENTATIVE_FACES
// (3) FaceEntity references, no matter how many faces eventually belong to the real person it
// represents - this is the actual memory fix: today's union-find components can grow to hold an
// unbounded share of a whole run's ungrouped-faces batch before a single one is ever promoted to a
// PersonEntity. Here, a cluster promotes (and is discarded from this class's own open-cluster list)
// the INSTANT its member count reaches 3, using exactly those 3 faces as the promotion payload.
// Callers are expected to immediately persist that promotion (personRecognitionService.addPerson)
// and make the resulting PersonEntity visible to their own existing-person matching path (bucket
// comparison via updatePerson) for every later face - both within the rest of the same slice and in
// later slices of the same run - so a face that would have been face #4+ for this same real person
// never reaches this class again and never risks seeding a second, duplicate person.
public class CandidateClusterer {

    private final double similarityThreshold;
    private final Duration maxGap;
    private final int maxRepresentativeFaces;
    private final List<CandidateCluster> openClusters = new ArrayList<>();

    public CandidateClusterer(double similarityThreshold, Duration maxGap, int maxRepresentativeFaces) {
        this.similarityThreshold = similarityThreshold;
        this.maxGap = maxGap;
        this.maxRepresentativeFaces = maxRepresentativeFaces;
    }

    /**
     * Feeds one face through the candidate-cluster matching/promotion logic: it joins the first
     * open cluster it matches (cosine similarity >= similarityThreshold against, and within maxGap
     * of, at least one of that cluster's held representatives), or seeds a brand-new one-member
     * cluster if it matches none. Returns the cluster's representative faces (exactly
     * maxRepresentativeFaces of them) if this call just promoted that cluster - the caller should
     * persist those as a brand-new person and make it visible to its own existing-person matching
     * for every subsequent face. Returns empty otherwise (the face joined or seeded a still-open,
     * not-yet-promoting cluster).
     */
    public Optional<List<FaceEntity>> add(FaceEntity face) {
        for (int i = 0; i < openClusters.size(); i++) {
            CandidateCluster cluster = openClusters.get(i);
            if (cluster.matches(face, similarityThreshold, maxGap)) {
                cluster.representatives.add(face);
                if (cluster.representatives.size() >= maxRepresentativeFaces) {
                    openClusters.remove(i);
                    return Optional.of(cluster.representatives);
                }
                return Optional.empty();
            }
        }
        openClusters.add(new CandidateCluster(face));
        return Optional.empty();
    }

    // The new in-memory-only data structure driving the memory fix - see this file's own header
    // comment. Deliberately no smarter "most diverse era" representative selection here: just the
    // first faces (in arrival order) that joined the cluster, up to maxRepresentativeFaces - a
    // possible future refinement if chronological feed order alone doesn't prove sufficient in
    // practice (see TODO.md's "Open questions - RESOLVED" #2).
    private static final class CandidateCluster {
        private final List<FaceEntity> representatives = new ArrayList<>();

        CandidateCluster(FaceEntity first) {
            representatives.add(first);
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
