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
                    // Stricter path matching for WebUntis.
                    val path = if (url.encodedPath.contains("/WebUntis", ignoreCase = true)) "/WebUntis" else "/"
                    
                    // Force authenticated session cookies. 
                    // This prevents anonymous session IDs (from redirects) from sticking.
                    stored.removeAll { it.name == "JSESSIONID" || it.name == "schoolname" }
                    
                    stored.add(Cookie.Builder()
                        .name("JSESSIONID")
                        .value(session.sessionId)
                        .domain(host)
                        .path(path)
                        .secure()
                        .httpOnly()
                        .build())

                    stored.add(Cookie.Builder()
                        .name("schoolname")
                        .value(session.schoolname)
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
        val path = request.url.encodedPath
        val isLoginEndpoint = path.contains("jsonrpc.do", ignoreCase = true) ||
                              path.contains("api/userdata/login", ignoreCase = true)

        val builder = request.newBuilder()
            // Use WebUntis official Android app UA — browser UAs can trigger WAF/bot protection
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10; WebUntis)")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8")
            // Origin always needed — WebUntis validates it for CSRF on POST endpoints
            .header("Origin", "https://$host")
            .header("Referer", "https://$host/WebUntis/")

        // X-Requested-With is only for authenticated API calls, not for login
        if (!isLoginEndpoint) {
            builder.header("X-Requested-With", "XMLHttpRequest")
        }

        chain.proceed(builder.build())
    }

    private val jsonSanitizer = Interceptor { chain ->
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)
        
        val contentType = response.header("Content-Type") ?: ""
        val body = response.body
        val rawBody = body?.string() ?: ""

        val isJson = contentType.contains("json", ignoreCase = true)
        val looksLikeJson = rawBody.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        
        // Detection of session expiry/redirect for API calls.
        val requestPath = originalRequest.url.encodedPath
        val responsePath = response.request.url.encodedPath
        
        val isApiCall = requestPath.contains("/api/") || requestPath.contains(".do")
        // Never intercept the login calls themselves — they legitimately POST to *.do
        // and the server may return HTML or redirect before the session is established.
        val isLoginCall = requestPath.contains("jsonrpc.do", ignoreCase = true) ||
                          requestPath.contains("j_spring_security_check", ignoreCase = true) ||
                          requestPath.contains("/api/rest/view/v1/auth", ignoreCase = true) ||
                          requestPath.contains("api/userdata/login", ignoreCase = true) ||
                          requestPath.contains("api/auth/logout", ignoreCase = true)
        val redirectedToAuth = responsePath.contains("index.do") || responsePath.contains("login.do")
        val isHtml = rawBody.contains("<html", ignoreCase = true) || rawBody.contains("<!DOCTYPE", ignoreCase = true)

        if (!isLoginCall && isApiCall && (redirectedToAuth || (isHtml && !isJson && !looksLikeJson))) {
            // Signal auth failure clearly to trigger retry logic in repository.
            val errorJson = """{"error":{"code":-32001,"message":"Session abgelaufen (Redirect)"}}"""
            response.newBuilder()
                .header("X-WebUntis-Session-Expired", "true")
                .code(401)
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
