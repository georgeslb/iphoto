package org.iphoto.mobile.model

data class ShareResponse(
    val id: String,
    val name: String,
    val albumId: String,
    val isPasswordProtected: Boolean,
    val expired: Boolean,
    val userMd5: String
)
