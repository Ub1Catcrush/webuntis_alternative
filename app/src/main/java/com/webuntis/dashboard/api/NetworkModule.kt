package com.webuntis.dashboard.api

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton


private class SanitizingLogger : HttpLoggingInterceptor.Logger {
    private val pattern = Regex(
        "(\"(?:password|passwd|secret)\"\\s*:\\s*\")([^\"]*)(\")",
        RegexOption.IGNORE_CASE
    )

    override fun log(message: String) {
        val safe = pattern.replace(message) { mr ->
            mr.groupValues[1] + "***"
        }
        android.util.Log.i("okhttp.OkHttpClient", safe)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideCookieJar(sessionManager: SessionManager): CookieJar {
        return object : CookieJar {
            private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                val existing = cookieStore.getOrPut(host) { mutableListOf() }
                for (newCookie in cookies) {
                    existing.removeAll { it.name == newCookie.name }
                    existing.add(newCookie)
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val host = url.host
                val stored = cookieStore.getOrPut(host) { mutableListOf() }.toMutableList()
                val session = sessionManager.session

                if (session != null && session.sessionId.isNotEmpty()) {
                    // Always inject the current session's JSESSIONID — overrides any stored value
                    // to avoid stale cookies from parallel logins.
                    stored.removeAll { it.name == "JSESSIONID" }
                    stored.add(Cookie.Builder()
                        .name("JSESSIONID").value(session.sessionId)
                        .domain(host).path("/").secure().httpOnly().build())

                    if (stored.none { it.name == "schoolname" }) {
                        stored.add(Cookie.Builder()
                            .name("schoolname").value(session.schoolname)
                            .domain(host).path("/").secure().build())
                    }
                    if (stored.none { it.name == "traceId" }) {
                        stored.add(Cookie.Builder()
                            .name("traceId").value("webuntis-dashboard")
                            .domain(host).path("/").secure().build())
                    }
                }
                return stored
            }
        }
    }

    /**
     * Sets headers that WebUntis expects — without a proper User-Agent the
     * server returns an HTML "Browser not supported" page.
     * Also injects the session token as a header for REST endpoints that
     * don't honor the cookie jar properly.
     */
    private fun makeHeadersInterceptor(sessionManager: SessionManager) = Interceptor { chain ->
        val session = sessionManager.session
        val host = chain.request().url.host
        val builder = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
        if (session != null && session.sessionId.isNotEmpty()) {
            builder.header("Referer", "https://$host/WebUntis/")
        }
        // Origin header is required for the REST login CSRF check.
        // Without it the server returns 403 on newer Android versions because
        // the User-Agent signals a modern browser that must pass origin validation.
        builder.header("Origin", "https://$host")
        chain.proceed(builder.build())
    }

    /**
     * Catches non-JSON responses (HTML error pages) and converts them to a
     * JSON error object so Gson doesn't crash.
     */
    private val jsonSanitizer = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type") ?: ""
        val rawBody = response.body?.string() ?: ""

        val isJson = contentType.contains("json", ignoreCase = true)
        val looksLikeJson = rawBody.trimStart().let { it.startsWith("{") || it.startsWith("[") }

        if (!isJson && !looksLikeJson && rawBody.isNotEmpty()) {
            val safeMessage = rawBody
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim().take(200).replace("\"", "'")
            val errorJson = """{"error":{"code":-1,"message":"$safeMessage"}}"""
            response.newBuilder()
                .body(errorJson.toResponseBody("application/json".toMediaType()))
                .build()
        } else {
            response.newBuilder()
                .body(rawBody.toResponseBody(
                    response.body?.contentType() ?: "application/json".toMediaType()
                ))
                .build()
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: CookieJar, sessionManager: SessionManager): OkHttpClient =
    // 1. Interceptor erstellen und auf BODY konfigurieren
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(makeHeadersInterceptor(sessionManager))
            .addInterceptor(jsonSanitizer)
            .addInterceptor(HttpLoggingInterceptor(SanitizingLogger()).apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
}
