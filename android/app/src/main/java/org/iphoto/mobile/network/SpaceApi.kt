package org.iphoto.mobile.network

import org.iphoto.mobile.model.SpaceDTO
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface SpaceApi {
    @GET("api/spaces")
    suspend fun list(): List<SpaceDTO>

    // Single space by id - used by SyncViewModel to check the current sync-lock state of the
    // paired Space on app start, before deciding whether to silently re-lock it or warn the user
    // that sync was stopped remotely (see SyncPrefs.lockConfirmed's own comment).
    @GET("api/spaces/{id}")
    suspend fun get(@Path("id") spaceId: String): SpaceDTO

    // Block/unblock-on-sync: called the moment a folder<->Space sync pairing is
    // configured/enabled (lockSync) or stopped (unlockSync) - see SyncViewModel. Authenticated as
    // this exact device (AuthInterceptor's Bearer token), which is what the server keys the lock
    // to (server: SpaceService#lockForSync/unlockFromSync).
    @POST("api/spaces/{id}/sync-lock")
    suspend fun lockSync(@Path("id") spaceId: String): SpaceDTO

    @DELETE("api/spaces/{id}/sync-lock")
    suspend fun unlockSync(@Path("id") spaceId: String): SpaceDTO
}
