package org.iphoto.mobile.model

data class DeviceLoginRequest(
    val username: String,
    val password: String,
    val deviceName: String?
)
