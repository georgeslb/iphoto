package org.iphoto.mobile

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import org.iphoto.mobile.auth.AuthRepository
import org.iphoto.mobile.data.TokenStore
import org.iphoto.mobile.network.AlbumApi
import org.iphoto.mobile.network.ApiClient
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.network.ShareApi
import org.iphoto.mobile.network.SpaceApi

/** Simple hand-rolled DI container - single Activity, small dependency graph, no DI framework needed. */
class AppContainer(context: Context) {
    val tokenStore = TokenStore(context)
    val apiClient = ApiClient(tokenStore)
    val authRepository = AuthRepository(tokenStore, apiClient)

    val photoApi: PhotoApi get() = apiClient.create()
    val albumApi: AlbumApi get() = apiClient.create()
    val spaceApi: SpaceApi get() = apiClient.create()
    val shareApi: ShareApi get() = apiClient.create()

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
