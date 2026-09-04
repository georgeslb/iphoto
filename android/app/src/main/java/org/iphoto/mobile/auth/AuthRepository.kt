package org.iphoto.mobile.auth

import android.os.Build
import org.iphoto.mobile.data.TokenStore
import org.iphoto.mobile.model.DeviceLoginRequest
import org.iphoto.mobile.model.UserDto
import org.iphoto.mobile.network.ApiClient
import org.iphoto.mobile.network.AuthApi
import org.iphoto.mobile.network.UserApi

class AuthRepository(
    private val tokenStore: TokenStore,
    private val apiClient: ApiClient
) {
    val isLoggedIn: Boolean get() = tokenStore.isLoggedIn
    val storedServerUrl: String? get() = tokenStore.serverUrl

    suspend fun login(serverUrl: String, username: String, password: String): UserDto {
        tokenStore.serverUrl = serverUrl
        val response = apiClient.create<AuthApi>().login(
            DeviceLoginRequest(
                username = username,
                password = password,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            )
        )
        tokenStore.token = response.token
        tokenStore.deviceTokenId = response.id
        return response.user
    }

    suspend fun currentUser(): UserDto = apiClient.create<UserApi>().me()

    /**
     * Revoking on the server is best-effort - a failed revoke call (offline, already revoked,
     * server unreachable) must never leave the user stuck "logged in" on this device, so the
     * local session is always cleared regardless of the network outcome.
     */
    suspend fun logout() {
        val deviceId = tokenStore.deviceTokenId
        try {
            if (!deviceId.isNullOrEmpty()) {
                apiClient.create<AuthApi>().revokeDevice(deviceId)
            }
        } catch (_: Exception) {
            // ignored - see kdoc above
        } finally {
            tokenStore.clearSession()
        }
    }
}
