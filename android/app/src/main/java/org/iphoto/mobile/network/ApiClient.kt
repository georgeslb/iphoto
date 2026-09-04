package org.iphoto.mobile.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.iphoto.mobile.BuildConfig
import org.iphoto.mobile.data.TokenStore
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The server URL is user-configurable (self-hosted, no fixed host) and can change at login time,
 * so Retrofit can't be a fixed singleton built once at construction - [retrofit] rebuilds it only
 * when the stored URL actually changes.
 */
class ApiClient(private val tokenStore: TokenStore) {

    // shared with Coil's ImageLoader (see AppContainer) so thumbnail/original image requests
    // carry the same Bearer auth header as the REST calls
    val httpClient: OkHttpClient
        get() = okHttpClient

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStore))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedRetrofit: Retrofit? = null

    @Synchronized
    private fun retrofit(): Retrofit {
        val baseUrl = normalizedBaseUrl()
        cachedRetrofit?.let { if (cachedBaseUrl == baseUrl) return it }

        val built = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        cachedRetrofit = built
        cachedBaseUrl = baseUrl
        return built
    }

    private fun normalizedBaseUrl(): String {
        val raw = tokenStore.serverUrl?.trim().orEmpty()
        require(raw.isNotEmpty()) { "Server URL is not set" }
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    inline fun <reified T> create(): T = retrofit().create(T::class.java)
}
