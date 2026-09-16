-- Face thumbnails move from an inline BYTEA blob (faces.face_thumbnail) to a file on disk,
-- referenced by path - see FaceEntity.thumbnailPath's own comment for why. face_thumbnail itself
-- is kept (not dropped here) until every existing row has been backfilled by
-- FaceThumbnailMigrationService - it can be dropped in a later migration once that's confirmed
-- done in production.
alter table faces add column thumbnail_path varchar(500);
