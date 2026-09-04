package org.iphoto.mobile.network

import org.iphoto.mobile.model.SpaceDTO
import retrofit2.http.GET

interface SpaceApi {
    @GET("api/spaces")
    suspend fun list(): List<SpaceDTO>
}
