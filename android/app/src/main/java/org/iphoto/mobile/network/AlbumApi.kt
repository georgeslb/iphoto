package org.iphoto.mobile.network

import org.iphoto.mobile.model.AlbumDTO
import retrofit2.http.GET

interface AlbumApi {
    @GET("api/albums")
    suspend fun list(): List<AlbumDTO>
}
