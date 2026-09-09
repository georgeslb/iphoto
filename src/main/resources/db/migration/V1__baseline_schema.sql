-- Baseline schema for a fresh database - replaces Hibernate's ddl-auto=update (see
-- application.yml's spring.jpa.hibernate.ddl-auto=validate). Assumes a genuinely new/empty
-- database; this is not a migration path off an existing ddl-auto=update-built schema.
--
-- Every index/constraint below follows one consistent <type>_<table>_<column(s)> naming
-- convention (uk_ = unique constraint, idx_ = plain index, fk_ = foreign key, ck_ = check
-- constraint, <table>_pkey = primary key), and every foreign-key column has a supporting index
-- (Postgres does NOT automatically index a FK column the way some other databases do) - verified
-- against the actual repository query methods (see FaceRepository/ShareRepository/etc.) rather
-- than added blindly; a column only used as part of some OTHER index/constraint's LEADING column
-- (e.g. duplicate_photos.photo_id, covered by uk_duplicate_photos_photo_id_duplicate_id) doesn't
-- get a redundant standalone one.
--
-- One correctness fix folded in here: "day" (immich_import_days) is quoted - unquoted, it's a
-- reserved keyword in H2 (interval/EXTRACT syntax; not reserved in Postgres) and silently failed
-- to create that table under the old ddl-auto=update in every test run (Hibernate's schema-update
-- tool doesn't halt on a single failed DDL statement by default) - invisible in production since
-- Postgres was never affected, and invisible in CI since nothing tested that table.

create table users (
    id uuid not null,
    username varchar(255),
    password varchar(255),
    role varchar(255),
    immich_base_url varchar(255),
    immich_api_key_encrypted varchar(255),
    immich_api_key_iv varchar(255),
    constraint users_pkey primary key (id),
    constraint uk_users_username unique (username)
);

create table spaces (
    id uuid not null,
    name varchar(255) not null,
    type text,
    user_id uuid not null,
    photo_count integer,
    is_garbage boolean,
    created_at timestamp,
    updated_at timestamp,
    constraint spaces_pkey primary key (id),
    constraint uk_spaces_name_user_id unique (name, user_id),
    constraint ck_spaces_type check (type in ('MAIN', 'SECONDARY')),
    constraint fk_spaces_user_id foreign key (user_id) references users (id)
);
create index idx_spaces_user_id on spaces (user_id);

create table albums (
    id uuid not null,
    name varchar(255) not null,
    description text,
    cover_photo_id uuid,
    photo_count integer,
    created_at timestamp,
    updated_at timestamp,
    space_id uuid not null,
    external_source varchar(32),
    external_id varchar(128),
    constraint albums_pkey primary key (id),
    constraint uk_albums_name_space_id unique (name, space_id),
    constraint uk_albums_external_source_external_id unique (external_source, external_id),
    constraint fk_albums_space_id foreign key (space_id) references spaces (id) on delete cascade
);
create index idx_albums_space_id on albums (space_id);
create index idx_albums_cover_photo_id on albums (cover_photo_id);

create table photos (
    id uuid not null,
    file_name varchar(255) not null,
    original_path varchar(1024) not null,
    user_id uuid not null,
    thumbnail_path varchar(1024),
    file_hash varchar(255) not null,
    file_size bigint,
    content_type varchar(255),
    date_taken timestamp,
    grouped_day date,
    file_system_creation_date timestamp,
    width integer,
    height integer,
    duration bigint,
    camera_model varchar(255),
    location varchar(255),
    is_favorite boolean not null,
    is_garbage boolean not null,
    phash bytea,
    mhash bytea,
    orb_descriptors bytea,
    orb_keypoints bytea,
    garbage_at timestamp,
    created_at timestamp,
    space_id uuid not null,
    constraint photos_pkey primary key (id),
    constraint uk_photos_file_hash_user_id unique (file_hash, user_id),
    constraint fk_photos_space_id foreign key (space_id) references spaces (id) on delete cascade,
    constraint fk_photos_user_id foreign key (user_id) references users (id)
);
create index idx_photos_space_id on photos (space_id);
create index idx_photos_space_id_is_garbage on photos (space_id, is_garbage);
create index idx_photos_space_id_grouped_day on photos (space_id, grouped_day);
create index idx_photos_user_id on photos (user_id);
-- Backs DuplicationService's candidate-window query
-- (findBySpaceIdAndDateTakenBetweenAndPhashNotNullAndMhashNotNullAndIsGarbageFalse).
create index idx_photos_space_id_date_taken on photos (space_id, date_taken);

-- Deferred FK: albums.cover_photo_id -> photos. albums is declared before photos above (so its
-- FK to spaces reads naturally alongside its other columns), but Postgres/H2 both require a
-- referenced table to already exist for an inline FK - added here instead, now that photos exists.
alter table albums add constraint fk_albums_cover_photo_id foreign key (cover_photo_id) references photos (id);

create table album_photos (
    album_id uuid not null,
    photo_id uuid not null,
    constraint album_photos_pkey primary key (album_id, photo_id),
    constraint fk_album_photos_album_id foreign key (album_id) references albums (id),
    constraint fk_album_photos_photo_id foreign key (photo_id) references photos (id)
);
-- album_id is already covered (leading column of the composite PK above); photo_id is not.
create index idx_album_photos_photo_id on album_photos (photo_id);

create table photo_metadata (
    id uuid not null,
    raw_metadata_json text,
    aperture varchar(255),
    shutter_speed varchar(255),
    iso varchar(255),
    focal_length varchar(255),
    lens_model varchar(255),
    latitude double precision,
    longitude double precision,
    codec varchar(255),
    bit_rate varchar(255),
    frame_rate varchar(255),
    constraint photo_metadata_pkey primary key (id),
    constraint fk_photo_metadata_id foreign key (id) references photos (id)
);

create table duplicates (
    id uuid not null,
    hash varchar(255),
    space_id uuid not null,
    constraint duplicates_pkey primary key (id),
    constraint uk_duplicates_hash unique (hash),
    constraint fk_duplicates_space_id foreign key (space_id) references spaces (id) on delete cascade
);
create index idx_duplicates_space_id on duplicates (space_id);

create table duplicate_photos (
    id uuid not null,
    status varchar(255),
    photo_id uuid not null,
    duplicate_id uuid not null,
    constraint duplicate_photos_pkey primary key (id),
    constraint uk_duplicate_photos_photo_id_duplicate_id unique (photo_id, duplicate_id),
    constraint ck_duplicate_photos_status check (status in ('IN_GROUP', 'UNLINKED', 'MOVED_TO_GARBAGE', 'UNLINKED_BUT_MOVED_TO_GARBAGE')),
    constraint fk_duplicate_photos_photo_id foreign key (photo_id) references photos (id) on delete cascade,
    constraint fk_duplicate_photos_duplicate_id foreign key (duplicate_id) references duplicates (id) on delete cascade
);
-- photo_id is already covered (leading column of the composite unique above); duplicate_id is not.
create index idx_duplicate_photos_duplicate_id on duplicate_photos (duplicate_id);

create table people (
    id uuid not null,
    name varchar(255),
    cover_face_id uuid,
    cover_face_origin varchar(255),
    space_id uuid not null,
    constraint people_pkey primary key (id),
    constraint uk_people_cover_face_id unique (cover_face_id),
    constraint ck_people_cover_face_origin check (cover_face_origin in ('CALCULATED', 'MANUAL')),
    constraint fk_people_space_id foreign key (space_id) references spaces (id) on delete cascade
);
create index idx_people_space_id on people (space_id);

create table faces (
    id uuid not null,
    photo_id uuid not null,
    person_id uuid,
    x integer not null,
    y integer not null,
    width integer not null,
    height integer not null,
    embedding bytea,
    face_thumbnail bytea,
    constraint faces_pkey primary key (id),
    constraint fk_faces_photo_id foreign key (photo_id) references photos (id) on delete cascade,
    constraint fk_faces_person_id foreign key (person_id) references people (id)
);
-- faces had NO index whatsoever before this - findByPersonIsNullFetchPhoto (WHERE person_id IS
-- NULL), findByPersonId, findByPersonIdAndPhotoId, and every join through photo_id/person_id were
-- full table scans.
create index idx_faces_photo_id on faces (photo_id);
create index idx_faces_person_id on faces (person_id);

-- Deferred FK (people.cover_face_id -> faces) needed faces to already exist.
alter table people add constraint fk_people_cover_face_id foreign key (cover_face_id) references faces (id);

create table face_link_exclusions (
    id uuid not null,
    face_id uuid not null,
    excluded_person_id uuid not null,
    created_at timestamp,
    constraint face_link_exclusions_pkey primary key (id),
    constraint uk_face_link_exclusions_face_id_excluded_person_id unique (face_id, excluded_person_id),
    constraint fk_face_link_exclusions_face_id foreign key (face_id) references faces (id) on delete cascade,
    constraint fk_face_link_exclusions_excluded_person_id foreign key (excluded_person_id) references people (id) on delete cascade
);
-- face_id is already covered (leading column of the composite unique above); excluded_person_id
-- is not.
create index idx_face_link_exclusions_excluded_person_id on face_link_exclusions (excluded_person_id);

create table person_embedding_buckets (
    id uuid not null,
    person_id uuid not null,
    bucket_year integer not null,
    face_count integer not null,
    embedding bytea,
    constraint person_embedding_buckets_pkey primary key (id),
    constraint uk_person_embedding_buckets_person_id_bucket_year unique (person_id, bucket_year),
    constraint fk_person_embedding_buckets_person_id foreign key (person_id) references people (id) on delete cascade
);
-- person_id is already covered (leading column of the composite unique above) - no extra index.

create table shares (
    id uuid not null,
    name varchar(255) not null,
    album_id uuid,
    qrcode text,
    paswword varchar(255),
    available_until timestamp,
    user_md5 varchar(255),
    created_at timestamp,
    updated_at timestamp,
    space_id uuid not null,
    external_source varchar(32),
    external_id varchar(128),
    constraint shares_pkey primary key (id),
    constraint uk_shares_name_space_id unique (name, space_id),
    constraint uk_shares_external_source_external_id unique (external_source, external_id),
    constraint fk_shares_space_id foreign key (space_id) references spaces (id) on delete cascade,
    constraint fk_shares_album_id foreign key (album_id) references albums (id) on delete cascade
);
create index idx_shares_space_id on shares (space_id);
create index idx_shares_album_id on shares (album_id);
-- Public guest-share lookup key (ShareRepository.findByUserMd5AndName) - had nothing backing it,
-- a full table scan on every unauthenticated share-link visit.
create index idx_shares_user_md5_name on shares (user_md5, name);

create table device_tokens (
    id uuid not null,
    user_id uuid not null,
    token_hash varchar(255) not null,
    device_name varchar(255),
    created_at timestamp not null,
    last_used_at timestamp,
    revoked_at timestamp,
    constraint device_tokens_pkey primary key (id),
    constraint uk_device_tokens_token_hash unique (token_hash),
    constraint fk_device_tokens_user_id foreign key (user_id) references users (id)
);
create index idx_device_tokens_user_id_created_at on device_tokens (user_id, created_at);

create table immich_import_days (
    id uuid not null,
    user_id uuid not null,
    "day" date not null,
    immich_asset_count integer,
    queued_count integer,
    imported_count integer,
    skipped_count integer,
    failed_count integer,
    status varchar(255) not null,
    last_attempted_at timestamp,
    last_synced_at timestamp,
    created_at timestamp,
    updated_at timestamp,
    constraint immich_import_days_pkey primary key (id),
    constraint uk_immich_import_days_day_user_id unique ("day", user_id),
    constraint ck_immich_import_days_status check (status in ('NOT_IMPORTED', 'IN_PROGRESS', 'COMPLETED', 'PARTIAL', 'FAILED')),
    constraint fk_immich_import_days_user_id foreign key (user_id) references users (id) on delete cascade
);
create index idx_immich_import_days_user_id_day on immich_import_days (user_id, "day");

create table tasks (
    id uuid not null,
    photo_id uuid,
    user_id uuid,
    task_type varchar(255) not null,
    status varchar(255) not null,
    payload text,
    error_message varchar(1000),
    stack_trace text,
    execute_at timestamp,
    created_at timestamp,
    updated_at timestamp,
    constraint tasks_pkey primary key (id),
    constraint ck_tasks_task_type check (task_type in ('PERMANENT_DELETE', 'PROCESS_UPLOAD', 'HASH_IMAGE', 'ACTIVATE_HEAVY_TASKS', 'CHECK_DUPLICATION', 'ISOLATE_FACES', 'FACE_RECOGNITION', 'SYNC_FROM_IMMICH', 'UPLOAD_FROM_IMMICH', 'IMPORT_IMMICH_ALBUM'))
    -- photo_id/user_id are plain UUID columns, not real foreign keys (TaskEntity never maps them
    -- as @ManyToOne) - no FK constraint here, matching current behavior.
);
create index idx_tasks_status on tasks (status);
create index idx_tasks_status_task_type on tasks (status, task_type);
create index idx_tasks_status_photo_id on tasks (status, photo_id);
-- These three don't share a leading column with the composites above:
-- findFirstByTaskTypeOrderByCreatedAtDesc/existsByTaskType (task_type alone),
-- findByUserIdAndTaskType (user_id alone), findByPhotoIdAndTaskTypeIn (photo_id, no status filter).
create index idx_tasks_task_type_created_at on tasks (task_type, created_at);
create index idx_tasks_user_id_task_type on tasks (user_id, task_type);
create index idx_tasks_photo_id_task_type on tasks (photo_id, task_type);
