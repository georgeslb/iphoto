package org.iphoto.mobile.network

import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.model.PhotoPageResponse
import org.iphoto.mobile.model.SpringPage
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PhotoApi {
    @GET("api/photos")
    suspend fun listMain(
        @Query("page") page: Int,
        @Query("size") size: Int
    ): PhotoPageResponse

    @GET("api/photos/{id}")
    suspend fun get(@Path("id") id: String): Photo

    @GET("api/albums/{id}/photos")
    suspend fun listForAlbum(
        @Path("id") albumId: String,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): SpringPage<Photo>

    @GET("api/spaces/{spaceId}/photos")
    suspend fun listForSpace(
        @Path("spaceId") spaceId: String,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): SpringPage<Photo>
}
