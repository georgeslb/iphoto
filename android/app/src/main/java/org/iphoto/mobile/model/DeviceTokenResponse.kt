package org.iphoto.mobile.model

data class DeviceTokenResponse(
    val id: String,
    val token: String,
    val deviceName: String?,
    val createdAt: String,
    val user: UserDto
)
