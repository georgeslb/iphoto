-- Persisted, incremental replacement for FaceRecognitionServiceImpl's old fully-in-memory,
-- run-scoped CandidateClusterer state (see TODO.md's 2026-09-12 "face recognition algorithm
-- rework" entry for the full rationale). Previously, every ~30s FACE_RECOGNITION run rebuilt its
-- whole "not-yet-a-person" clustering state from scratch by re-feeding up to
-- MAX_UNGROUPED_FACES_PER_RUN OLD (already-examined-at-least-once) faces through a brand-new
-- CandidateClusterer every single time - the same faces, re-compared against the same candidate
-- groupings, over and over, for as long as a real person's faces took to accumulate the 3 needed
-- to promote. This table lets an open (1-2 member, not-yet-promoted) candidate cluster survive
-- between runs: a face joins one exactly once (see faces.candidate_cluster_id below) and is never
-- refetched/reprocessed again afterward - only genuinely NEW (never-before-examined) faces get
-- processed each run, against this much smaller set of still-open clusters instead of the whole
-- backlog.
--
-- No embedding/representative columns here - a cluster's representative faces are looked up via
-- faces.candidate_cluster_id (a plain FK, not a join table) instead of being duplicated here,
-- since FaceEntity already carries everything (embedding, photo.dateTaken) a match check needs.
create table candidate_clusters (
    id uuid not null,
    space_id uuid not null,
    created_at timestamp not null,
    constraint candidate_clusters_pkey primary key (id),
    constraint fk_candidate_clusters_space_id foreign key (space_id) references spaces (id) on delete cascade
);

-- Nullable - only set while a face sits in an open (not yet promoted) cluster; cleared back to
-- null the moment it's promoted into a real person (see PersonRecognitionService#addPerson) or its
-- cluster expires/is wiped (see AdminServiceImpl#restartPersonRecognition's bulk reset, which nulls
-- this in the SAME statement as person/status - explicit, not relying on an ON DELETE behavior, to
-- match how faces.person_id is already handled there).
alter table faces add column candidate_cluster_id uuid null;
alter table faces add constraint fk_faces_candidate_cluster_id foreign key (candidate_cluster_id) references candidate_clusters (id);
create index idx_faces_candidate_cluster_id on faces (candidate_cluster_id);

-- FaceRecognitionServiceImpl's main backlog query now selects by status = 'NEW' alone (a face
-- already examined once - matched, joined/spawned a cluster, or promoted - is flipped to OLD and
-- never needs reconsidering; see FaceStatus's own comment) instead of "person IS NULL", so this
-- replaces idx_faces_person_id as the hot index for that query. person_id is still looked up
-- directly by id/FK joins elsewhere (PersonRepository etc.), which don't need a dedicated index on
-- faces.person_id itself.
create index idx_faces_status on faces (status);

-- One-time backfill: any face that was already OLD (examined at least once under the old,
-- stateless-replay design) but still unmatched (never got a person, and by definition has no
-- candidate_cluster_id yet - this column didn't exist before this migration) would otherwise be
-- permanently stranded - the new query only ever looks at status = 'NEW', so an old-and-unmatched
-- face would simply never be reconsidered again. Reset it to NEW to give it one fresh pass under
-- the new persisted-cluster logic instead of silently losing it.
update faces set status = 'NEW' where status = 'OLD' and person_id is null;
