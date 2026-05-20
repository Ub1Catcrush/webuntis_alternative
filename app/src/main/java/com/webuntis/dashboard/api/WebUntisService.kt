package com.webuntis.dashboard.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface WebUntisService {

    // ── Bearer Token ──────────────────────────────────────────────────────────
    @GET("api/token/new")
    suspend fun getBearerToken(): Response<ResponseBody>

    // ── Auth: JSON-RPC ────────────────────────────────────────────────────────
    @POST("jsonrpc.do")
    suspend fun jsonRpcLogin(
        @Query("school") school: String,
        @Body body: JsonRpcRequest
    ): Response<ResponseBody>

    // ── Auth: REST ────────────────────────────────────────────────────────────
    @POST("api/userdata/login")
    suspend fun restLogin(
        @Query("school") school: String,
        @Body body: LoginRequest
    ): Response<ResponseBody>

    @POST("api/auth/logout")
    suspend fun logout(): Response<ResponseBody>

    // ── JSON-RPC (timetable) ──────────────────────────────────────────────────
    @POST("jsonrpc.do")
    suspend fun jsonRpc(
        @Query("school") school: String,
        @Body body: JsonRpcRequest
    ): Response<ResponseBody>

    // ── Timetable REST v1 ─────────────────────────────────────────────────────
    @GET("api/rest/view/v1/timetable/entries")
    suspend fun getTimetableV1(
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("format") format: Int = 2,
        @Query("resourceType") resourceType: String,
        @Query("resources") resources: String,
        @Query("periodTypes") periodTypes: String = "",
        @Query("timetableType") timetableType: String = "MY_TIMETABLE",
        @Query("layout") layout: String = "START_TIME"
    ): Response<ResponseBody>

    @GET("api/rest/view/v1/timetable/entries")
    suspend fun getTimetableV1Auth(
        @Header("Authorization") authorization: String,
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("format") format: Int = 2,
        @Query("resourceType") resourceType: String,
        @Query("resources") resources: String,
        @Query("periodTypes") periodTypes: String = "",
        @Query("timetableType") timetableType: String = "MY_TIMETABLE",
        @Query("layout") layout: String = "START_TIME"
    ): Response<ResponseBody>

    // ── Calendar Entry Detail (v2) — carries substText, lessonInfo per entry ──
    @GET("api/rest/view/v2/calendar-entry/detail")
    suspend fun getCalendarEntryDetail(
        @Header("Authorization") authorization: String,
        @Query("elementId") elementId: Int,
        @Query("elementType") elementType: Int,
        @Query("startDateTime") startDateTime: String,
        @Query("endDateTime") endDateTime: String,
        @Query("homeworkOption") homeworkOption: String = "DUE"
    ): Response<ResponseBody>

    // ── Homework ──────────────────────────────────────────────────────────────
    @GET("api/homeworks/lessons")
    suspend fun getHomework(
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): Response<ResponseBody>

    // ── Exams / Events ────────────────────────────────────────────────────────
    @GET("api/exams")
    suspend fun getExamsForStudent(
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("studentId") studentId: Int
    ): Response<ResponseBody>

    @GET("api/calendar-entry/list/student")
    suspend fun getCalendarEvents(
        @Query("rangeStart") rangeStart: String,
        @Query("rangeEnd") rangeEnd: String
    ): Response<ResponseBody>

    // ── Classbook ─────────────────────────────────────────────────────────────
    @GET("api/classreg/classregevents")
    suspend fun getClassbookEntriesForParent(
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("studentId") studentId: Int
    ): Response<ResponseBody>

    @GET("api/classreg/entriesForStudent")
    suspend fun getClassbookEntriesForStudent(
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): Response<ResponseBody>

    @GET("api/classreg/entries")
    suspend fun getClassbookEntries(
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): Response<ResponseBody>

    // ── Messages ──────────────────────────────────────────────────────────────
    @GET("api/rest/view/v1/messages")
    suspend fun getMessages(): Response<ResponseBody>

    @GET("api/rest/view/v1/messages")
    suspend fun getMessagesAuth(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @GET("api/rest/view/v1/messages/{id}")
    suspend fun getMessageDetail(
        @Path("id") id: Int,
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @GET("api/rest/view/v1/messages/{attachmentId}/attachmentstorageurl")
    suspend fun getAttachmentStorageUrl(
        @Path("attachmentId") attachmentId: String,
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @GET
    suspend fun downloadFromStorage(
        @Url url: String,
        @Header("x-amz-server-side-encryption-customer-algorithm") encAlgorithm: String,
        @Header("x-amz-server-side-encryption-customer-key") encKey: String,
        @Header("x-amz-server-side-encryption-customer-key-md5") encKeyMd5: String
    ): Response<ResponseBody>

    @GET("api/rest/view/v1/messages/status")
    suspend fun getMessagesStatus(): Response<ResponseBody>

    // ── Absences ──────────────────────────────────────────────────────────────
    @GET("api/classreg/absences/students")
    suspend fun getAbsences(
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("studentId") studentId: Int,
        @Query("excuseStatusId") excuseStatusId: Int = -1
    ): Response<ResponseBody>
}

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Map<String, Any>,
    val id: String = System.currentTimeMillis().toString()
)

data class LoginRequest(
    val user: String,
    val password: String,
    val client: String = "android"
)
