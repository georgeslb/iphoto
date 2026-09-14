package org.iphoto.mobile.model

data class SpaceDTO(
    val id: String,
    val name: String,
    val photoCount: Int?,
    val createdAt: String,
    val updatedAt: String,
    val albumAllowed: Boolean,
    val allowCopyToMain: Boolean,
    val deletionAllowed: Boolean,
    val deleted: Boolean,
    // Block/unblock-on-sync (server: SpaceEntity.syncLockedByDevice) - true while this Space is
    // actively mirrored to an on-device folder. syncLockedByDeviceName names the device (this
    // one, once SyncViewModel has locked it - see SyncRepository.lockForSync/unlockFromSync).
    val syncLocked: Boolean = false,
    val syncLockedByDeviceName: String? = null
)
