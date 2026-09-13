package org.iphoto.mobile.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.model.PhotoPageResponse
import org.iphoto.mobile.model.SpringPage
import org.iphoto.mobile.model.UpdatePhotoDateRequest
import org.iphoto.mobile.model.UploadChunkResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
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

    // Backs the device-folder sync feature's garbage/restore reconciliation (see
    // org.iphoto.mobile.sync.SyncRepository) - the web frontend has no equivalent call, it only
    // ever browses garbage cross-space via the day-indexed /api/photos/garbage/* views.
    @GET("api/spaces/{spaceId}/garbage")
    suspend fun listGarbageForSpace(
        @Path("spaceId") spaceId: String,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): SpringPage<Photo>

    // Backs the device-folder sync feature (see org.iphoto.mobile.sync). Mirrors the same
    // multipart contract the web frontend's chunked uploader uses against UploadController.
    @Multipart
    @POST("api/upload/chunk")
    suspend fun uploadChunk(
        @Part("uploadId") uploadId: RequestBody,
        @Part("fileName") fileName: RequestBody,
        @Part("fileSize") fileSize: RequestBody,
        @Part("chunkIndex") chunkIndex: RequestBody,
        @Part("totalChunks") totalChunks: RequestBody,
        @Part("contentType") contentType: RequestBody,
        @Part("spaceId") spaceId: RequestBody,
        @Part chunk: MultipartBody.Part
    ): UploadChunkResponse

    @POST("api/photos/garbage")
    suspend fun moveToGarbage(@Body ids: List<String>)

    @POST("api/photos/garbage/restore")
    suspend fun restoreFromGarbage(@Body ids: List<String>)

    @PATCH("api/photos/{id}/date")
    suspend fun updateDate(@Path("id") id: String, @Body request: UpdatePhotoDateRequest): Photo
}
