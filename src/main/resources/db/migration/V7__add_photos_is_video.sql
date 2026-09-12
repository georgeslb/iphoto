-- Backs the photo/video type filter on Library/Space/Album/Garbage (PhotoEntity.isVideo,
-- kept in sync with content_type via onCreate/onUpdate for every row written from here on).
-- Existing rows are backfilled once from their already-stored content_type.
alter table photos add column is_video boolean;
update photos set is_video = (content_type like 'video/%');
alter table photos alter column is_video set not null;

create index idx_photos_space_id_is_garbage_is_video on photos (space_id, is_garbage, is_video);
