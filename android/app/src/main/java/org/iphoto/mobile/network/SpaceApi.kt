package org.iphoto.mobile.network

import org.iphoto.mobile.model.SpaceDTO
import retrofit2.http.GET
import retrofit2.http.Path

interface SpaceApi {
    @GET("api/spaces")
    suspend fun list(): List<SpaceDTO>

    @GET("api/spaces/{id}")
    suspend fun get(@Path("id") spaceId: String): SpaceDTO
}
