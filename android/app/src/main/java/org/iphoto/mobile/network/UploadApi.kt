package org.iphoto.mobile.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.iphoto.mobile.model.UploadChunkResponse
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface UploadApi {
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
}
