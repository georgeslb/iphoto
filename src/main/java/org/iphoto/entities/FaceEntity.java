package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "faces", indexes = {
        @Index(columnList = "photo_id", name = "idx_faces_photo_id"),
        @Index(columnList = "person_id", name = "idx_faces_person_id"),
        @Index(columnList = "candidate_cluster_id", name = "idx_faces_candidate_cluster_id"),
        @Index(columnList = "status", name = "idx_faces_status")
})
@Getter
@Setter
@SuperBuilder
public class FaceEntity { // Extends BaseEntity

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id", nullable = false)
    private PhotoEntity photo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id")
    private PersonEntity person;

    // Set while this face sits in a still-open (not yet promoted) CandidateClusterEntity - see
    // that entity's own comment and CandidateClusterer for the full incremental-clustering design.
    // Cleared (along with status/person) by AdminServiceImpl#restartPersonRecognition's bulk reset.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_cluster_id")
    private CandidateClusterEntity candidateCluster;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(columnDefinition = "BYTEA")
    private byte[] embedding; // Stores the 128-dimensional float array for recognition

    // LEGACY - being migrated to filesystem storage (see thumbnailPath below and
    // FaceThumbnailMigrationService). No longer written for newly detected faces
    // (FaceDetectionServiceImpl writes straight to disk instead) - a face row still carrying a
    // non-null value here predates that change and hasn't been backfilled yet. Kept (not dropped)
    // until every existing row has been migrated, so old rows keep working via
    // FaceController#getThumbnail's fallback in the meantime. Do not read this directly for new
    // code - go through thumbnailPath / the /api/faces/{id}/thumbnail endpoint.
    @Column(columnDefinition = "BYTEA")
    private byte[] faceThumbnail;

    // Filesystem path to the cropped JPEG thumbnail (iphoto.storage.faceThumbnails, same
    // {year}/{month} layout PhotoStoragePathResolver uses for photo originals/thumbnails) - moved
    // out of the DB because base64-inlining a BYTEA blob into every FaceDTO (Persons grid, Lightbox
    // "Faces Detected" strip, merge-suggestion cover photos) made those responses slow to build and
    // slow to load on the frontend, and loading it as part of a person's `faces` collection (a
    // plain lazy @OneToMany, no batch-fetch) meant every face-detach/delete path paid to pull every
    // one of a person's thumbnails into memory just to recalculate a cover face - see TODO.md.
    private String thumbnailPath;

    // NEW until one groupFaces() run has analysed it (matched, promoted, or left an unclustered
    // singleton), then flipped to OLD - see FaceStatus and FaceRecognitionServiceImpl's
    // groupFacesForUser. @Builder.Default so every existing FaceEntity.builder() call site (test
    // fixtures included) gets NEW without having to set it explicitly, matching a freshly detected
    // face's real starting state.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FaceStatus status = FaceStatus.NEW;


    public FaceEntity() {
        super();
    }
}