package com.webuntis.dashboard.api

import android.util.Log
import com.webuntis.dashboard.BuildConfig
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
                    // Android 15 Fix: Stricter cookie matching.
                    // We must ensure JSESSIONID and schoolname are present with the Secure flag.
                    // Path matching is also stricter; we try to match the base context.
                    val path = if (url.encodedPath.contains("/WebUntis", ignoreCase = true)) "/WebUntis" else "/"
                    
                    // 1. JSESSIONID
                    stored.removeAll { it.name == "JSESSIONID" }
                    stored.add(Cookie.Builder()
                        .name("JSESSIONID")
                        .value(session.sessionId)
                        .domain(host)
                        .path(path)
                        .secure()
                        .httpOnly()
                        .build())

                    // 2. schoolname
                    stored.removeAll { it.name == "schoolname" }
                    stored.add(Cookie.Builder()
                        .name("schoolname")
                        .value(session.schoolname) // Raw value, no quotes
                        .domain(host)
                        .path(path)
                        .secure()
                        .build())
                }
                return stored
            }
        }
    }

    private fun makeHeadersInterceptor(sessionManager: SessionManager) = Interceptor { chain ->
        val session = sessionManager.session
        val request = chain.request()
        val host = request.url.host
        val builder = request.newBuilder()
            
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
        
        if (session != null && session.sessionId.isNotEmpty()) {
            builder.header("Referer", "https://$host/WebUntis/")
        }
        
        // Android 15 Fix: Only add Origin/Referer for state-changing requests or when useful.
        // Some servers reject GET requests with an Origin header if they don't expect it.
        if (request.method != "GET") {
            builder.header("Origin", "https://$host")
        }

        chain.proceed(builder.build())
    }

    private val jsonSanitizer = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type") ?: ""
        
        val body = response.body
        val rawBody = body?.string() ?: ""

        val isJson = contentType.contains("json", ignoreCase = true)
        val looksLikeJson = rawBody.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        
        val isExpiredHtml = rawBody.contains("login.do", ignoreCase = true) && 
                           rawBody.contains("<html", ignoreCase = true)

        if ((!isJson && !looksLikeJson && rawBody.isNotEmpty()) || isExpiredHtml) {
            val safeMessage = if (isExpiredHtml) "Session abgelaufen" else {
                rawBody.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim().take(200).replace("\"", "'")
            }
            
            val errorCode = if (isExpiredHtml) -32001 else -1
            val errorJson = """{"error":{"code":$errorCode,"message":"$safeMessage"}}"""
            response.newBuilder()
                .header("X-WebUntis-Session-Expired", if (isExpiredHtml) "true" else "false")
                .body(errorJson.toResponseBody("application/json".toMediaType()))
                .build()
        } else {
            response.newBuilder()
                .body(rawBody.toResponseBody(body?.contentType()))
                .build()
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: CookieJar, sessionManager: SessionManager): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .addInterceptor(makeHeadersInterceptor(sessionManager))
            .addInterceptor(jsonSanitizer)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor(SanitizingLogger()).apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .build()
}
