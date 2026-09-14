package org.iphoto.mobile.model

data class AlbumCreateRequest(
    val name: String,
    val description: String? = null,
    val coverPhotoId: String? = null
)
