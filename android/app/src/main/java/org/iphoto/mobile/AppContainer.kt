package org.iphoto.mobile

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
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

    // Fresh each call, like the *Api properties above - always bound to today's server URL/token.
    fun syncRepository(): SyncRepository = SyncRepository(context, photoApi, taskApi, syncHashCache)

    // shares the auth-carrying OkHttpClient so thumbnail/original image requests authenticate
    // the same way REST calls do
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .okHttpClient(apiClient.httpClient)
            .build()
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
