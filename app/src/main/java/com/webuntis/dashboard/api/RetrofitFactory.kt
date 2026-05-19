package com.webuntis.dashboard.api

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates a fresh Retrofit instance with the correct base URL for the current session.
 * Call [get] after login to get a properly-configured service.
 */
@Singleton
class RetrofitFactory @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    fun create(server: String): WebUntisService {
        val baseUrl = "https://$server/WebUntis/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(WebUntisService::class.java)
    }
}
