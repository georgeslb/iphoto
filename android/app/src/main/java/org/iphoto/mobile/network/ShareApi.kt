package org.iphoto.mobile.network

import org.iphoto.mobile.model.ShareResponse
import retrofit2.http.GET

interface ShareApi {
    @GET("api/share/my-shares")
    suspend fun myShares(): List<ShareResponse>
}
