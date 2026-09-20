package org.iphoto.mobile

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.iphoto.mobile.auth.AuthRepository
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.data.TokenStore
import org.iphoto.mobile.network.AlbumApi
import org.iphoto.mobile.network.ApiClient
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.network.ShareApi
import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.network.TaskApi
import org.iphoto.mobile.sync.HashCache
import org.iphoto.mobile.sync.SyncRepository

/** Simple hand-rolled DI container - single Activity, small dependency graph, no DI framework needed. */
class AppContainer(private val context: Context) {
    val tokenStore = TokenStore(context)
    val apiClient = ApiClient(tokenStore)
    val authRepository = AuthRepository(tokenStore, apiClient)

    val photoApi: PhotoApi get() = apiClient.create()
    val albumApi: AlbumApi get() = apiClient.create()
    val spaceApi: SpaceApi get() = apiClient.create()
    val shareApi: ShareApi get() = apiClient.create()
    val taskApi: TaskApi get() = apiClient.create()

    val syncPrefs = SyncPrefs(context)
    private val syncHashCache = HashCache(context)

    // Outlives any single screen/ViewModel - for background work that must finish even if the
    // user navigates away before a ViewModel's own viewModelScope would otherwise cancel it. This
    // container is held for the whole process lifetime (IPhotoApplication's own `by lazy`), so
    // this scope is too. Specifically added for mirroring a "move to garbage"/"move to Main + also
    // remove from device" onto the locally synced folder (SyncRepository.trashLocalFilesForHashes)
    // - that's a full folder scan+hash, slow enough that awaiting it inside viewModelScope was
    // either blocking the UI (fixed once) or, once fixed to launch independently but still on
    // viewModelScope, getting silently cancelled mid-flight if the user navigated away from the
    // screen before it finished - confirmed on a real device: the server-side move succeeded but
    // the device file was never actually trashed.
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Fresh each call, like the *Api properties above - always bound to today's server URL/token.
    fun syncRepository(): SyncRepository = SyncRepository(context, photoApi, taskApi, syncHashCache)

    // shares the auth-carrying OkHttpClient so thumbnail/original image requests authenticate
    // the same way REST calls do
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { apiClient.httpClient }))
                add(coil3.video.VideoFrameDecoder.Factory())
            }
            .build()
    }

    // 150 MB LRU disk cache for video playback - avoids re-downloading previously streamed chunks,
    // enables smooth rewinding/looping, and bridges short network stalls.
    @get:OptIn(UnstableApi::class)
    private val videoCache: SimpleCache by lazy {
        val cacheDir = File(context.cacheDir, "video_cache")
        SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(150 * 1024 * 1024L),
            StandaloneDatabaseProvider(context)
        )
    }

    // Uses apiClient.videoHttpClient - a *separate* authenticated OkHttpClient from the one
    // REST/thumbnail/sync-upload traffic shares, with its own connection pool - not
    // apiClient.httpClient the way imageLoader above shares it for Coil. OkHttp defaults to 5
    // concurrent requests per host shared across everything on one client; video's own range
    // requests were competing with sync's concurrent chunk uploads for that budget, visibly
    // slowing/stalling playback while a sync was active.
    // Wrapped in CacheDataSource.Factory so streamed video chunks are persisted to disk cache.
    @OptIn(UnstableApi::class)
    fun videoDataSourceFactory(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(apiClient.videoHttpClient)
        return CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun absoluteUrl(relativeUrlOrPath: String): String {
        if (relativeUrlOrPath.startsWith("http")) return relativeUrlOrPath
        val base = tokenStore.serverUrl?.trim().orEmpty().trimEnd('/')
        return base + relativeUrlOrPath
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided - wrap content in CompositionLocalProvider(LocalAppContainer provides ...)")
}
