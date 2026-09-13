package org.iphoto.mobile.network

import org.iphoto.mobile.model.ShareRequest
import org.iphoto.mobile.model.ShareResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ShareApi {
    @GET("api/share/my-shares")
    suspend fun myShares(): List<ShareResponse>

    @POST("api/share")
    suspend fun createShare(@Body request: ShareRequest): ShareResponse
}
