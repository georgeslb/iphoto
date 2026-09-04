package org.iphoto.mobile.network

import org.iphoto.mobile.model.UserDto
import retrofit2.http.GET

interface UserApi {
    @GET("api/user/me")
    suspend fun me(): UserDto
}
