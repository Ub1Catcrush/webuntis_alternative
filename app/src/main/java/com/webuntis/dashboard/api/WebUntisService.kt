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

    // Returns userData with elemId/elemType — used to resolve child element ID for parent accounts
    @GET("api/rest/view/v1/app/data")
    suspend fun getAppData(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

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
        @Header("Authorization") authorization: String?,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): Response<ResponseBody>

    /**
     * List attachments for a homework entry.
     */
    @GET("api/homeworks/{homeworkId}/attachments")
    suspend fun getHomeworkAttachments(
        @Header("Authorization") authorization: String?,
        @Path("homeworkId") homeworkId: Int
    ): Response<ResponseBody>

    /**
     * Download a homework attachment directly as a byte stream.
     */
    @GET("api/homeworks/{homeworkId}/attachments/{attachmentId}")
    @Streaming
    suspend fun downloadHomeworkAttachment(
        @Header("Authorization") authorization: String?,
        @Path("homeworkId")   homeworkId:   Int,
        @Path("attachmentId") attachmentId: String
    ): Response<ResponseBody>

    // ── Exams / Events ────────────────────────────────────────────────────────
    @GET("api/exams")
    suspend fun getExamsForStudent(
        @Header("Authorization") authorization: String?,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("studentId") studentId: Int
    ): Response<ResponseBody>

    @GET("api/calendar-entry/list/student")
    suspend fun getCalendarEvents(
        @Header("Authorization") authorization: String?,
        @Query("rangeStart") rangeStart: String,
        @Query("rangeEnd") rangeEnd: String
    ): Response<ResponseBody>

    // ── Classbook ─────────────────────────────────────────────────────────────
    @GET("api/classreg/classregevents")
    suspend fun getClassbookEntriesForParent(
        @Header("Authorization") authorization: String?,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("studentId") studentId: Int
    ): Response<ResponseBody>

    @GET("api/classreg/entriesForStudent")
    suspend fun getClassbookEntriesForStudent(
        @Header("Authorization") authorization: String?,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): Response<ResponseBody>

    @GET("api/classreg/entries")
    suspend fun getClassbookEntries(
        @Header("Authorization") authorization: String?,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): Response<ResponseBody>

    // ── Messages ──────────────────────────────────────────────────────────────
    @GET("api/rest/view/v1/messages")
    suspend fun getMessagesAuth(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @GET("api/rest/view/v1/messages/{id}")
    suspend fun getMessageDetail(
        @Path("id") id: Int,
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    // Draft detail — different endpoint, returns storageAttachments + full content
    @GET("api/rest/view/v1/messages/drafts/{id}")
    suspend fun getDraftDetail(
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
    suspend fun getMessagesStatus(
        @Header("Authorization") authorization: String?
    ): Response<ResponseBody>

    // Sent messages
    @GET("api/rest/view/v1/messages/sent")
    suspend fun getSentMessagesAuth(
        @Header("Authorization") authorization: String,
        @Query("folder") folder: String = "SENT"
    ): Response<ResponseBody>

    // Draft messages
    @GET("api/rest/view/v1/messages/drafts")
    suspend fun getDraftsAuth(
        @Header("Authorization") authorization: String,
        @Query("folder") folder: String = "DRAFTS"
    ): Response<ResponseBody>

    // Send a new message / reply
    @POST("api/rest/view/v1/messages")
    suspend fun sendMessage(
        @Header("Authorization") authorization: String,
        @Body body: okhttp3.RequestBody
    ): Response<ResponseBody>

    // Save new draft (multipart: JSON "request" part, optional "attachments" parts)
    @Multipart
    @POST("api/rest/view/v2/messages/drafts")
    suspend fun saveDraft(
        @Header("Authorization") authorization: String,
        @Part request: okhttp3.MultipartBody.Part,
        @Part attachments: List<okhttp3.MultipartBody.Part> = emptyList()
    ): Response<ResponseBody>

    // Update existing draft (same multipart format, PUT with draft id)
    @Multipart
    @PUT("api/rest/view/v2/messages/drafts/{id}")
    suspend fun updateDraft(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int,
        @Part request: okhttp3.MultipartBody.Part,
        @Part attachments: List<okhttp3.MultipartBody.Part> = emptyList()
    ): Response<ResponseBody>

    // Delete message / draft
    @DELETE("api/rest/view/v1/messages/{id}")
    suspend fun deleteMessage(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int
    ): Response<ResponseBody>

    // Recipients list (for message compose picker)
    @GET("api/rest/view/v1/messages/recipients/static/persons")
    suspend fun getMessageRecipientsAuth(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    // ── School Years ─────────────────────────────────────────────────────────
    @GET("api/rest/view/v1/schoolyears")
    suspend fun getSchoolYears(
        @Header("Authorization") authorization: String?
    ): Response<ResponseBody>

    // ── Timegrid ──────────────────────────────────────────────────────────────
    @GET("api/public/timegrid")
    suspend fun getTimegrid(
        @Header("Authorization") authorization: String?,
        @Query("schoolyearId") schoolyearId: Int
    ): Response<ResponseBody>

    // ── Absences ──────────────────────────────────────────────────────────────
    @GET("api/classreg/absences/students")
    suspend fun getAbsences(
        @Header("Authorization") authorization: String?,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("studentId") studentId: Int,
        @Query("excuseStatusId") excuseStatusId: Int = -1
    ): Response<ResponseBody>

    @GET("api/classreg/absences/meta")
    suspend fun getAbsencesMeta(
        @Header("Authorization") authorization: String?
    ): Response<ResponseBody>

    @POST("api/classreg/absences/students/self")
    suspend fun createAbsence(
        @Header("Authorization") authorization: String?,
        @Header("Tenant-Id") tenantId: String?,
        @Body body: CreateAbsenceRequest
    ): Response<ResponseBody>

    @PUT("api/classreg/absences/students/self/{id}")
    suspend fun updateAbsence(
        @Header("Authorization") authorization: String?,
        @Header("Tenant-Id") tenantId: String?,
        @Path("id") id: Int,
        @Body body: CreateAbsenceRequest
    ): Response<ResponseBody>

    // Correct bulk-delete endpoint: DELETE /students with body {"absenceIds":[id]}
    @HTTP(method = "DELETE", path = "api/classreg/absences/students", hasBody = true)
    suspend fun deleteAbsence(
        @Header("Authorization") authorization: String?,
        @Header("Tenant-Id") tenantId: String?,
        @Body body: DeleteAbsenceRequest
    ): Response<ResponseBody>

    // ── Parent account endpoints (personType=12) ──────────────────────────────
    // Parents must use /students/{studentId} instead of /students/self

    @POST("api/classreg/absences/students/{studentId}")
    suspend fun createAbsenceForStudent(
        @Header("Authorization") authorization: String?,
        @Path("studentId") studentId: Int,
        @Body body: CreateAbsenceRequest
    ): Response<ResponseBody>

    @PUT("api/classreg/absences/students/{studentId}/{id}")
    suspend fun updateAbsenceForStudent(
        @Header("Authorization") authorization: String?,
        @Path("studentId") studentId: Int,
        @Path("id") id: Int,
        @Body body: CreateAbsenceRequest
    ): Response<ResponseBody>

}

@Keep
data class JsonRpcRequest(
    @SerializedName("jsonrpc") val jsonrpc: String = "2.0",
    @SerializedName("method") val method: String,
    @SerializedName("params") val params: Map<String, Any>,
    @SerializedName("id") val id: String = System.currentTimeMillis().toString()
)

@Keep
data class LoginRequest(
    @SerializedName("user") val user: String,
    @SerializedName("password") val password: String,
    @SerializedName("client") val client: String = "android"
)

@Keep
data class CreateAbsenceRequest(
    val startDate: Int,
    val startTime: Int,
    val endDate: Int,
    val endTime: Int,
    val text: String,
    val reasonId: Int,
    val studentId: Int
)

@Keep
data class DeleteAbsenceRequest(
    val absenceIds: List<Int>
)
