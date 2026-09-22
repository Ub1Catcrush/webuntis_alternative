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

    /**
     * Like [create], but with its own private, in-memory cookie jar instead of the shared
     * singleton one.
     *
     * The shared client's CookieJar always injects the PRIMARY account's JSESSIONID into every
     * request to a given host (see NetworkModule.provideCookieJar) — necessary for the normal
     * single-session case, but fatal for a second WebUntis login on the same host (see
     * WebUntisRepository.fetchMessagesForSecondAccount): its own jsonrpc.do login legitimately
     * establishes its own session, but the very next call — getBearerToken(), which takes no
     * credential of its own and mints a token for "whoever the current session cookie says you
     * are" — would get the shared jar's injected PRIMARY cookie instead of the one this login
     * just set. The result: a "second account" bearer token that's actually scoped to the
     * primary account, so its messages come back as a second, differently-labeled copy of the
     * primary's own — which is exactly what looked like "second account not loading / merged
     * with the primary" in practice.
     */
    fun createIsolated(server: String): WebUntisService {
        val baseUrl = "https://$server/WebUntis/"
        val isolatedClient = okHttpClient.newBuilder()
            .cookieJar(object : okhttp3.CookieJar {
                private val store = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    val existing = store.getOrPut(url.host) { mutableListOf() }
                    for (c in cookies) {
                        existing.removeAll { it.name == c.name }
                        existing.add(c)
                    }
                }
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                    store[url.host]?.toList() ?: emptyList()
            })
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(isolatedClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(WebUntisService::class.java)
    }
}
