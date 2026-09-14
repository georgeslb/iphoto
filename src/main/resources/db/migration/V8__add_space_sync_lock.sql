-- Backs SpaceEntity.syncLockedByDevice / the block-unblock-on-sync feature: while a Space is
-- actively mirrored to an on-device folder (Android app), it's locked to that exact device token
-- so a web-session write can't corrupt the mirror out from under it (see
-- SpaceService#assertSyncWritable / DeviceTokenAuthenticationFilter). ON DELETE SET NULL is a
-- defensive backstop only - device_tokens rows are soft-revoked (revokedAt), never hard-deleted in
-- normal operation, so the real unlock-on-revoke path is DeviceTokenServiceImpl#revoke publishing
-- DeviceTokenRevokedEvent (see SpaceSyncLockListener), not this FK action.
alter table spaces add column sync_locked_by_device_id uuid;
alter table spaces add constraint fk_spaces_sync_locked_by_device_id
    foreign key (sync_locked_by_device_id) references device_tokens (id) on delete set null;
