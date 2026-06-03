package com.webuntis.dashboard.api

import com.webuntis.dashboard.model.GithubRelease
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GithubService {
    @GET("repos/Ub1Catcrush/webuntis_alternative/releases/latest")
    suspend fun getLatestRelease(): Response<GithubRelease>

    @GET
    @Streaming
    suspend fun downloadFile(@Url url: String): Response<ResponseBody>
}
