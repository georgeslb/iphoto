package org.iphoto.mobile.network

import org.iphoto.mobile.model.DeviceLoginRequest
import org.iphoto.mobile.model.DeviceTokenResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Path

interface AuthApi {
    @POST("api/auth/mobile/login")
    suspend fun login(@Body request: DeviceLoginRequest): DeviceTokenResponse

    @DELETE("api/auth/mobile/devices/{id}")
    suspend fun revokeDevice(@Path("id") id: String)
}
