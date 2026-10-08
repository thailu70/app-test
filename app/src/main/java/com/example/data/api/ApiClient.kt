package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // Default base URL for Android Emulator -> VPS / Host server
    // User or admin can update this dynamically if connecting to an external VPS IP.
    @Volatile
    private var baseUrl: String = "http://10.0.2.2:3000/"

    @Volatile
    private var authToken: String? = null

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        authToken?.let { token ->
            if (token.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
        }

        requestBuilder.header("Accept", "application/json")
        chain.proceed(requestBuilder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
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
}
