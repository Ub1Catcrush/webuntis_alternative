package com.webuntis.dashboard.api

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
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

    /**
     * List attachments for a homework entry.
     * Returns a JSON array of attachment objects (id, name, contentType, size etc.)
     * Homework attachments are NOT the same as message storage attachments —
     * they are served directly, no presigned URL / S3 step needed.
     */
    @GET("api/homeworks/{homeworkId}/attachments")
    suspend fun getHomeworkAttachments(
        @Path("homeworkId") homeworkId: Int
    ): Response<ResponseBody>

    /**
     * Download a homework attachment directly as a byte stream.
     * The server responds with the file content and Content-Disposition header.
     */
    @GET("api/homeworks/{homeworkId}/attachments/{attachmentId}")
    @Streaming
    suspend fun downloadHomeworkAttachment(
        @Path("homeworkId")   homeworkId:   Int,
        @Path("attachmentId") attachmentId: String
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

@Keep // Verhindert, dass R8 die Klasse anrührt
data class JsonRpcRequest(
    @SerializedName("jsonrpc") val jsonrpc: String = "2.0",
    @SerializedName("method") val method: String,
    @SerializedName("params") val params: Map<String, Any>,
    @SerializedName("id") val id: String = System.currentTimeMillis().toString()
)

@Keep // Verhindert, dass R8 die Klasse anrührt
data class LoginRequest(
    @SerializedName("user") val user: String,
    @SerializedName("password") val password: String,
    @SerializedName("client") val client: String = "android"
)
