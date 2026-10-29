package com.example.data.api

import com.example.BuildConfig
import com.example.core.environment.ApiSessionScope
import com.example.core.environment.AppEnvironment
import com.example.core.environment.isIsolatedReviewDescriptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.URI

object ApiClient {

    // Build with -PROUTEPASS_API_BASE_URL=https://api.yourdomain.com/ for each environment.
    // API requests and portal links share the host selected by this build.
    val DEFAULT_HTTPS_URL: String = BuildConfig.ROUTEPASS_API_BASE_URL
    val DEFAULT_VPS_HOST: String
        get() = DEFAULT_HTTPS_URL.removePrefix("https://").removeSuffix("/")
    const val DEFAULT_HTTP_DEV_URL = "http://10.0.2.2:3000/"

    // Valid HTTPS is the production Android default
    @Volatile
    private var baseUrl: String = DEFAULT_HTTPS_URL

    @Volatile
    private var authToken: String? = null
    @Volatile private var environmentGeneration = 0L
    @Volatile private var verifiedReviewGeneration: Long? = null
    private val reviewVerificationLock = Any()

    fun sessionScope(): ApiSessionScope = synchronized(this) { ApiSessionScope(baseUrl, environmentGeneration) }
    fun isCurrent(scope: ApiSessionScope): Boolean = scope == sessionScope()
    fun environment(): AppEnvironment = AppEnvironment.forBaseUrl(getBaseUrl())
    private fun authTokenFor(scope: ApiSessionScope): String? = synchronized(this) {
        if (!isCurrent(scope)) throw IOException("The environment changed. Open this screen again.")
        authToken
    }

    /** No bearer or private guest capability is sent until the demo identifies itself. */
    private fun verifyReviewService(scope: ApiSessionScope) {
        if (AppEnvironment.forBaseUrl(scope.baseUrl) != AppEnvironment.DEMO || verifiedReviewGeneration == scope.generation) return
        synchronized(reviewVerificationLock) {
            if (verifiedReviewGeneration == scope.generation) return
            if (!isCurrent(scope)) throw IOException("The environment changed. Open this screen again.")
            val request = Request.Builder().url(scope.baseUrl + "api/review/info").header("Accept", "application/json").build()
            val descriptor = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The demo service is unavailable. Please try again.")
                runCatching { moshi.adapter(Map::class.java).fromJson(response.body?.string().orEmpty()) }.getOrNull()
            }
            if (!isCurrent(scope) || !isIsolatedReviewDescriptor(descriptor))
                throw IOException("The isolated demo service could not be verified. No demo sign-in or private data was sent.")
            verifiedReviewGeneration = scope.generation
        }
    }

    // Security Policy: Never log sensitive authentication tokens, passwords, PINs, or PII in production
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("X-Telebirr-Signature")
        redactHeader("X-Idempotency-Key")
        redactHeader("X-Booking-Secret")
        redactHeader("X-Request-Secret")
        level = HttpLoggingInterceptor.Level.NONE
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    @Volatile
    private var retrofitInstance: Retrofit? = null

    @Volatile
    private var serviceInstance: RoutePassApiService? = null

    fun getService(): RoutePassApiService {
        return serviceInstance ?: synchronized(this) {
            serviceInstance ?: createRetrofit().create(RoutePassApiService::class.java).also {
                serviceInstance = it
            }
        }
    }

    fun <T> createFeatureService(serviceClass: Class<T>): T = createRetrofit().create(serviceClass)

    /** A running trip cannot accidentally upload with a later account's bearer token. */
    fun <T> createSessionService(serviceClass: Class<T>, token: String): T {
        require(token.isNotBlank())
        val scope = sessionScope()
        return Retrofit.Builder().baseUrl(scope.baseUrl).client(scopedClient(scope, token))
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(serviceClass)
    }

    private fun scopedClient(scope: ApiSessionScope, explicitToken: String? = null): OkHttpClient =
        okHttpClient.newBuilder().addInterceptor { chain ->
            if (!isCurrent(scope) || !scope.containsUrl(chain.request().url.toString()))
                throw IOException("The environment changed. Open this screen again.")
            verifyReviewService(scope)
            val request = chain.request().newBuilder().header("Accept", "application/json")
            if (explicitToken != null) request.header("Authorization", "Bearer $explicitToken")
            else if (chain.request().header("Authorization") == null) {
                authTokenFor(scope)?.takeIf(String::isNotBlank)?.let { request.header("Authorization", "Bearer $it") }
            }
            val response = chain.proceed(request.build())
            if (!isCurrent(scope)) {
                response.close()
                throw IOException("The environment changed. Open this screen again.")
            }
            response
        }.build()

    private fun createRetrofit(): Retrofit {
        val scope = sessionScope()
        return Retrofit.Builder()
            .baseUrl(scope.baseUrl)
            .client(scopedClient(scope))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build().also {
                retrofitInstance = it
            }
    }

    @Synchronized
    fun setAuthToken(token: String?, scope: ApiSessionScope = sessionScope()) {
        check(isCurrent(scope)) { "The environment changed. Please sign in again." }
        authToken = token
    }

    fun getAuthToken(): String? = authToken

    @Synchronized
    fun setBaseUrl(newUrl: String) {
        require(newUrl.isNotBlank())
        val canonical = newUrl.trimEnd('/') + "/"
        val parsed = URI(canonical)
        require(parsed.scheme in setOf("https", "http") && parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null)
        if (canonical != baseUrl) {
            ++environmentGeneration
            verifiedReviewGeneration = null
            authToken = null
            baseUrl = canonical
            okHttpClient.dispatcher.cancelAll()
            okHttpClient.connectionPool.evictAll()
            serviceInstance = null
            retrofitInstance = null
        }
    }

    fun getBaseUrl(): String = baseUrl

    fun cancelRequests() { okHttpClient.dispatcher.cancelAll() }

    fun webSocketUrl(): String = getBaseUrl().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "ws"

    fun portalUrl(path: String = ""): String {
        val scope = sessionScope()
        val base = URI(scope.baseUrl)
        val result = when {
            URI(path).isAbsolute -> path
            base.path != "/" && path.startsWith(base.path) -> URI(base.scheme, base.authority, null, null, null).toString() + path
            else -> scope.baseUrl + path.trimStart('/')
        }
        require(scope.containsUrl(result)) { "This link belongs to another environment." }
        return result
    }
}
