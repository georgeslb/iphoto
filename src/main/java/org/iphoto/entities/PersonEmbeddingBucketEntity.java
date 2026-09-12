package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

// A running-average embedding for one person, scoped to one calendar year of their photos - see
// PersonRecognitionService's addFaceToBucket/removeFaceFromBucket/mergeBuckets. Exists purely to
// let FaceRecognitionService compare a new unmatched face against a SMALL, precomputed number of
// representative vectors per candidate person (one per year they have faces in) instead of
// querying and decoding a fresh set of that person's raw faces for every single (face, person)
// comparison - the same "precompute once at write time, cheap read at match time" pattern
// DuplicationService already uses for phash/mhash, applied here to face recognition.
//
// Bucketed by year (not averaged across a person's whole history) specifically to preserve the
// property the old per-comparison "temporal neighbor" window existed for: a person's appearance
// can change a lot across years (aging, hairstyle, ...), so blending every era into one global
// average would dilute the signal - see FaceRecognitionService's THRESHOLD comment.
//
// Pure derived/cache data, nothing else references it - cascades away entirely when its person is
// deleted (see PersonEntity#embeddingBuckets), unlike FaceEntity which deliberately survives a
// person's deletion with its person reference just nulled out.
@Entity
@Table(name = "person_embedding_buckets", uniqueConstraints = {
        @UniqueConstraint(name = "uk_person_embedding_buckets_person_id_bucket_year", columnNames = {"person_id", "bucket_year"})
}, indexes = {
        // Dedicated index for findByPersonId/findByPersonIdIn (see the repository) - the unique
        // constraint above already covers person_id as a leftmost prefix, this is a narrower index
        // for that hot read path (see V6 migration's own comment).
        @Index(name = "idx_person_embedding_buckets_person_id", columnList = "person_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonEmbeddingBucketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PersonEntity person;

    @Column(name = "bucket_year", nullable = false)
    private int bucketYear;

    // How many faces have been folded into embedding below - needed to weight future incremental
    // add/remove updates correctly (see PersonRecognitionService).
    @Column(name = "face_count", nullable = false)
    private int faceCount;

    // Running-average embedding vector, same float32-big-endian byte layout as
    // FaceEntity.embedding (see util.EmbeddingCodec) - not a real face's embedding, a derived
    // average of every face folded into this bucket so far.
    @Column(columnDefinition = "BYTEA")
    private byte[] embedding;

    // Shared by PersonRecognitionService (writing buckets) and FaceRecognitionService (reading
    // them for matching) - both MUST bucket a face the exact same way, or a face written into one
    // bucket could never be found under the year a later read expects. Faces with no valid
    // dateTaken bucket under year 0 rather than being skipped - the rest of this codebase already
    // assumes dateTaken is populated whenever a face exists, so this is a defensive fallback for
    // an already-assumed-not-to-happen case, not a new gap.
    public static int bucketYearFor(FaceEntity face) {
        PhotoEntity photo = face.getPhoto();
        return photo != null && photo.getDateTaken() != null ? photo.getDateTaken().getYear() : 0;
    }
}
