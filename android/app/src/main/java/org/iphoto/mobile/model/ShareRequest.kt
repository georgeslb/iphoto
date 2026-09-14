package org.iphoto.mobile.model

data class ShareRequest(
    val name: String,
    val daysAvailable: Int,
    val password: String?,
    val albumId: String
)
