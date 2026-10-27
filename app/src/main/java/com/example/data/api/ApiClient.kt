package com.example.data.api

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

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

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        authToken?.takeIf { originalRequest.header("Authorization") == null }?.let { token ->
            if (token.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
        }

        requestBuilder.header("Accept", "application/json")
        chain.proceed(requestBuilder.build())
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
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
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
        val client = okHttpClient.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        }.build()
        return Retrofit.Builder().baseUrl(getBaseUrl().trimEnd('/') + "/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(serviceClass)
    }

    private fun createRetrofit(): Retrofit {
        val sanitizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(sanitizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build().also {
                retrofitInstance = it
            }
    }

    fun setAuthToken(token: String?) {
        authToken = token
    }

    fun getAuthToken(): String? = authToken

    fun setBaseUrl(newUrl: String) {
        if (newUrl.isNotBlank() && newUrl != baseUrl) {
            baseUrl = newUrl
            synchronized(this) {
                serviceInstance = null
                retrofitInstance = null
            }
        }
    }

    fun getBaseUrl(): String = baseUrl

    fun portalUrl(path: String = ""): String = getBaseUrl().trimEnd('/') + "/" + path.trimStart('/')
}
