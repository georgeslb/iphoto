package org.iphoto.mobile.network

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.iphoto.mobile.BuildConfig
import org.iphoto.mobile.data.TokenStore
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val TAG = "ApiClient"

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

    // Explicit (matches OkHttp's own defaults) so the timeout that fires on an unreachable host
    // is a known, loggable number rather than "whatever OkHttp defaults to this version".
    private val connectTimeoutSeconds = 10L

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(tokenStore))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                })
            }
        }
        .build()

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedRetrofit: Retrofit? = null

    // internal + @PublishedApi, not private: the inline reified create() below needs to call
    // this across its inlined call sites, which private visibility wouldn't allow.
    @Synchronized
    @PublishedApi
    internal fun retrofit(): Retrofit {
        val baseUrl = normalizedBaseUrl()
        cachedRetrofit?.let { if (cachedBaseUrl == baseUrl) return it }

        Log.d(TAG, "Building Retrofit for baseUrl=$baseUrl (connectTimeout=${connectTimeoutSeconds}s)")
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
