package org.iphoto.mobile.model

data class AlbumDTO(
    val id: String,
    val name: String,
    val description: String?,
    val coverPhoto: Photo?,
    val photoCount: Int?,
    val createdAt: String,
    val updatedAt: String
)
