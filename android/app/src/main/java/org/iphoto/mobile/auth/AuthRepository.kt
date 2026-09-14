package org.iphoto.mobile.auth

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iphoto.mobile.data.TokenStore
import org.iphoto.mobile.model.DeviceLoginRequest
import org.iphoto.mobile.model.UserDto
import org.iphoto.mobile.network.ApiClient
import org.iphoto.mobile.network.AuthApi
import org.iphoto.mobile.network.UserApi

private const val TAG = "AuthRepository"

class AuthRepository(
    private val tokenStore: TokenStore,
    private val apiClient: ApiClient
) {
    val isLoggedIn: Boolean get() = tokenStore.isLoggedIn
    val storedServerUrl: String? get() = tokenStore.serverUrl
    val rememberMe: Boolean get() = tokenStore.rememberMe

    // withContext(Dispatchers.IO): every tokenStore.xxx = ... write below goes through
    // KeystoreEncryptedPreferences, which does real Android Keystore crypto (Cipher.doFinal(),
    // and on the very first call ever, KeyGenerator.generateKey() against the Keystore - a
    // genuinely slow, blocking operation on plenty of devices). AuthViewModel.login() calls this
    // from viewModelScope.launch { }, which defaults to Dispatchers.Main.immediate - without this,
    // all of that crypto work ran directly on the UI thread, freezing the window on tap. The
    // network call was never the problem (Retrofit's suspend functions already run off Main via
    // OkHttp's own dispatcher), but wrapping the whole body is simplest and harmless.
    suspend fun login(serverUrl: String, username: String, password: String, rememberMe: Boolean): UserDto =
        withContext(Dispatchers.IO) {
            tokenStore.serverUrl = serverUrl
            tokenStore.rememberMe = rememberMe
            Log.d(TAG, "login: dispatching POST api/auth/mobile/login to $serverUrl")
            val response = try {
                apiClient.create<AuthApi>().login(
                    DeviceLoginRequest(
                        username = username,
                        password = password,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                    )
                )
            } catch (e: Exception) {
                // e.message alone often just says "timeout" - the exception's actual class
                // (SocketTimeoutException vs UnknownHostException vs ConnectException) tells you
                // whether the host is unreachable, DNS failed, or the port is closed/firewalled.
                Log.e(TAG, "login: request to $serverUrl failed (${e::class.simpleName}): ${e.message}", e)
                throw e
            }
            Log.d(TAG, "login: success, userId=${response.user.id}")
            tokenStore.token = response.token
            tokenStore.deviceTokenId = response.id
            response.user
        }

    suspend fun currentUser(): UserDto = apiClient.create<UserApi>().me()

    /**
     * Drops a session that was never meant to survive this app process (rememberMe was unchecked
     * at login) - called on the *next* cold start, not this one, so the user isn't logged out
     * mid-use. No server-side revoke: the device token itself is still valid, this device just
     * stops holding onto it locally, same as any other "forgot to log out" token expiry.
     */
    fun forgetUnrememberedSession() {
        tokenStore.clearSession()
    }

    /**
     * Revoking on the server is best-effort - a failed revoke call (offline, already revoked,
     * server unreachable) must never leave the user stuck "logged in" on this device, so the
     * local session is always cleared regardless of the network outcome.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
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
