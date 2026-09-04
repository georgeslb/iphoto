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
    val deleted: Boolean
)
