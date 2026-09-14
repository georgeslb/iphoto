package org.iphoto.mobile.network

import org.iphoto.mobile.model.AlbumCreateRequest
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.model.AlbumPhotoRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path

interface AlbumApi {
    @GET("api/albums")
    suspend fun list(): List<AlbumDTO>

    @GET("api/albums/{id}")
    suspend fun get(@Path("id") id: String): AlbumDTO

    @POST("api/albums")
    suspend fun create(@Body request: AlbumCreateRequest): AlbumDTO

    @POST("api/albums/{id}/photos")
    suspend fun addPhotos(@Path("id") albumId: String, @Body request: AlbumPhotoRequest): AlbumDTO

    // Retrofit's @DELETE doesn't support a request body, hence @HTTP with hasBody - matches the
    // backend's DELETE /api/albums/{id}/photos, which takes the photo ids in the body rather than
    // as query params.
    @HTTP(method = "DELETE", path = "api/albums/{id}/photos", hasBody = true)
    suspend fun removePhotos(@Path("id") albumId: String, @Body request: AlbumPhotoRequest): AlbumDTO
}
