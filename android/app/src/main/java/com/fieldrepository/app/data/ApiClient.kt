package com.fieldrepository.app.data

import com.fieldrepository.app.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object ApiClient {
    fun create(tokenStore: TokenStore): FieldRepositoryApi {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            // The API serializes Prisma Decimal columns (measurements, costs) as JSON *strings*
            // (e.g. "12.5"). isLenient lets numeric DTO fields decode from quoted values, and
            // coerceInputValues falls back to defaults if a non-null field arrives null — together
            // these stop a single measured record from failing an entire list deserialization.
            isLenient = true
            coerceInputValues = true
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            // Mobile data is slower and drops connections more than Wi-Fi, so allow generous timeouts
            // and let OkHttp retry a connection that fails mid-handshake (e.g. a NAT64 path settling).
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = tokenStore.getToken()
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
    }
}
