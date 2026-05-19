package com.webuntis.dashboard.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.webuntis.dashboard.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.StringReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebUntisRepository @Inject constructor(
    private val retrofitFactory: RetrofitFactory,
    private val sessionManager: SessionManager
) {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val isoFmt  = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    @Volatile var cachedClassElementId: Int? = null
    @Volatile private var bearerToken: String? = null
    private val loginMutex = Mutex()

    private val gson: Gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    private fun service(): WebUntisService {
        val host = sessionManager.session?.server ?: error("Kein Server")
        return retrofitFactory.create(host)
    }

    private fun LocalDate.toUntis() = format(dateFmt)
    private fun LocalDate.toIso()   = format(isoFmt)

    private inline fun <reified T> parseJson(raw: String): T {
        val cleaned = raw.trimStart('\uFEFF').trim()
        val reader = JsonReader(StringReader(cleaned)).apply { setStrictness(Strictness.LENIENT) }
        return gson.fromJson(reader, TypeToken.get(T::class.java).type)
    }

    private fun <T> parseJson(raw: String, token: TypeToken<T>): T {
        val cleaned = raw.trimStart('\uFEFF').trim()
        val reader = JsonReader(StringReader(cleaned)).apply { setStrictness(Strictness.LENIENT) }
        return gson.fromJson(reader, token.type)
    }

    private fun rawBody(r: retrofit2.Response<okhttp3.ResponseBody>): String? {
        if (!r.isSuccessful) {
            val msg = tryExtractMessage(r.errorBody()?.string() ?: "") ?: "HTTP ${r.code()}"
            throw Exception(msg)
        }
        val raw = r.body()?.string()?.trim() ?: return null
        if (raw.contains("<html", ignoreCase = true)) throw Exception("Session abgelaufen")
        return raw.ifEmpty { null }
    }

    private fun tryExtractMessage(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val je = JsonParser.parseString(raw).asJsonObject
            je["message"]?.asString ?: je["error"]?.asJsonObject?.get("message")?.asString
        } catch (_: Exception) {
            raw.replace(Regex("<[^>]+>"), " ").trim().take(150).ifEmpty { null }
        }
    }

    // ── Bearer Token ──────────────────────────────────────────────────────────

    private suspend fun fetchBearerToken(): String? {
        return try {
            val resp = service().getBearerToken()
            if (!resp.isSuccessful) return null
            val raw = resp.body()?.string()?.trim() ?: return null
            val token = if (raw.startsWith("{")) {
                JsonParser.parseString(raw).asJsonObject.get("token")?.asString ?: raw
            } else raw.trim('"')
            token.also { bearerToken = it }
        } catch (_: Exception) { null }
    }

    private suspend fun callTimetableV1(
        startIso: String, endIso: String, classId: Int
    ): retrofit2.Response<okhttp3.ResponseBody> {
        val token = bearerToken ?: fetchBearerToken()
        val resp = if (token != null) {
            service().getTimetableV1Auth(
                authorization = "Bearer $token",
                start = startIso, end = endIso,
                resourceType = "STUDENT", resources = classId.toString()
            )
        } else {
            service().getTimetableV1(
                start = startIso, end = endIso,
                resourceType = "STUDENT", resources = classId.toString()
            )
        }
        if (resp.code() in listOf(401, 403)) {
            val fresh = fetchBearerToken() ?: return resp
            return service().getTimetableV1Auth(
                authorization = "Bearer $fresh",
                start = startIso, end = endIso,
                resourceType = "STUDENT", resources = classId.toString()
            )
        }
        return resp
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun login(
        server: String, schoolname: String, username: String, password: String
    ): Result<SessionData> = loginMutex.withLock {
        val rpc = loginViaJsonRpc(server, schoolname, username, password)
        val result = if (rpc.isSuccess) {
            sessionManager.storedCredentials = Pair(username, password)
            rpc
        } else {
            val rest = loginViaRest(server, schoolname, username, password)
            if (rest.isSuccess) sessionManager.storedCredentials = Pair(username, password)
            rest
        }
        if (result.isSuccess && result.getOrNull()?.personType == 12) {
            fetchBearerToken()
            getHomework()
        }
        result
    }

    private suspend fun loginViaJsonRpc(
        server: String, schoolname: String, username: String, password: String
    ): Result<SessionData> {
        return try {
            val body = JsonRpcRequest(
                method = "authenticate",
                params = mapOf("user" to username, "password" to password, "client" to "android")
            )
            val response = retrofitFactory.create(server).jsonRpcLogin(schoolname, body)
            val raw = response.body()?.string()?.trim()
                ?: return Result.failure(Exception("Leere Antwort"))
            val rpcResp: JsonRpcResponse<AuthResult> =
                parseJson(raw, object : TypeToken<JsonRpcResponse<AuthResult>>() {})
            if (rpcResp.error != null) return Result.failure(Exception(rpcResp.error.message))
            val res = rpcResp.result ?: return Result.failure(Exception("Keine Daten"))
            val session = SessionData(
                server = server, schoolname = schoolname, username = username,
                sessionId = res.sessionId, personId = res.personId ?: 0,
                classId = res.classId ?: 0, personName = res.personName ?: username,
                personType = res.personType ?: 0
            )
            sessionManager.session = session
            Result.success(session)
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun loginViaRest(
        server: String, schoolname: String, username: String, password: String
    ): Result<SessionData> {
        return try {
            val response = retrofitFactory.create(server).restLogin(
                schoolname, LoginRequest(username, password))
            if (!response.isSuccessful) {
                val msg = tryExtractMessage(response.errorBody()?.string() ?: "") ?: "HTTP ${response.code()}"
                return Result.failure(Exception(msg))
            }
            val raw = response.body()?.string()?.trim()
                ?: return Result.failure(Exception("Leere Antwort"))
            val loginResp: LoginResponse = parseJson(raw)
            val data = loginResp.data ?: return Result.failure(Exception("Ungültige Antwort"))
            val sessionId = data.sessionId ?: return Result.failure(Exception("Keine Session-ID"))
            val session = SessionData(
                server = server, schoolname = schoolname, username = username,
                sessionId = sessionId, personId = data.person?.id ?: 0,
                classId = data.schoolyearData?.klasse?.id ?: 0,
                personName = data.person?.name ?: username
            )
            sessionManager.session = session
            Result.success(session)
        } catch (e: Exception) { Result.failure(Exception("Anmeldung fehlgeschlagen: ${e.message}", e)) }
    }

    suspend fun logout() {
        try { service().logout() } catch (_: Exception) {}
        cachedClassElementId = null
        bearerToken = null
        sessionManager.clearSession()
    }

    // ── Timetable ─────────────────────────────────────────────────────────────

    private suspend fun fetchLessonsInRange(
        startDate: String, endDate: String
    ): Result<List<Lesson>> {
        val session = sessionManager.session
            ?: return Result.failure(Exception("Nicht angemeldet"))
        val classId = cachedClassElementId ?: 0

        if (session.personType == 12) {
            if (classId == 0) return Result.failure(Exception("Schüler-ID nicht verfügbar"))
            val startIso = "${startDate.substring(0,4)}-${startDate.substring(4,6)}-${startDate.substring(6,8)}"
            val endIso   = "${endDate.substring(0,4)}-${endDate.substring(4,6)}-${endDate.substring(6,8)}"
            return try {
                val response = callTimetableV1(startIso, endIso, classId)
                if (!response.isSuccessful)
                    return Result.failure(Exception("Stundenplan HTTP ${response.code()}"))
                val raw = response.body()?.string()?.trim()
                    ?: return Result.failure(Exception("Leere Antwort"))
                if (raw.contains("<html", ignoreCase = true))
                    return Result.failure(Exception("Session abgelaufen"))
                val ttResp: TimetableV1Response = parseJson(raw)
                Result.success(ttResp.toLessons())
            } catch (e: Exception) {
                Result.failure(Exception("Stundenplan: ${e.message}", e))
            }
        }

        val (id, type) = when (session.personType) {
            1    -> Pair(session.personId, 2)
            else -> Pair(session.personId, 5)
        }
        return try {
            val rpc = JsonRpcRequest(
                method = "getTimetable",
                params = mapOf("id" to id, "type" to type,
                    "startDate" to startDate, "endDate" to endDate)
            )
            val response = service().jsonRpc(session.schoolname, rpc)
            val raw = rawBody(response) ?: return Result.success(emptyList())
            val rpcResp = parseJson(raw, object : TypeToken<JsonRpcResponse<List<Lesson>>>() {})
            if (rpcResp.error != null) return Result.failure(Exception(rpcResp.error.message))
            Result.success(rpcResp.result ?: emptyList())
        } catch (e: Exception) {
            Result.failure(Exception("Stundenplan: ${e.message}", e))
        }
    }

    suspend fun getTwoSchoolDays(): Result<List<TimetableDay>> {
        val today = LocalDate.now()
        val rangeResult = fetchLessonsInRange(today.toUntis(), today.plusDays(21).toUntis())
        if (rangeResult.isFailure) return Result.failure(rangeResult.exceptionOrNull()!!)
        val byDate = rangeResult.getOrThrow()
            .groupBy { it.date }.entries
            .filter { (d, _) -> !untisIntToDate(d).isBefore(today) && untisIntToDate(d).dayOfWeek.value <= 5 }
            .sortedBy { it.key }.take(5)
            .map { (d, lessons) -> TimetableDay(untisIntToDate(d), lessons.sortedBy { it.startTime }) }
        return Result.success(byDate)
    }

    private fun untisIntToDate(d: Int): LocalDate {
        val s = d.toString().padStart(8, '0')
        return LocalDate.of(s.substring(0,4).toInt(), s.substring(4,6).toInt(), s.substring(6,8).toInt())
    }

    // ── Homework ──────────────────────────────────────────────────────────────

    suspend fun getHomework(): Result<Pair<List<Homework>, Map<String, String>>> {
        return try {
            val start = LocalDate.now().minusDays(14).toUntis()
            val end   = LocalDate.now().plusDays(21).toUntis()
            val response = service().getHomework(start, end)
            val raw = rawBody(response) ?: return Result.success(Pair(emptyList(), emptyMap()))
            val hwResp: HomeworkResponse = parseJson(raw)
            hwResp.data?.records?.firstOrNull()?.elementIds?.firstOrNull()?.let {
                if (it != 0) cachedClassElementId = it
            }
            val lessonMap = hwResp.data?.lessons
                ?.filter { it.id != null && !it.subject.isNullOrBlank() }
                ?.associate { it.id.toString() to (it.subject ?: "") }
                ?: emptyMap()
            Result.success(Pair(hwResp.data?.homeworks ?: emptyList(), lessonMap))
        } catch (e: Exception) { Result.failure(Exception("Hausaufgaben: ${e.message}", e)) }
    }

    // ── Classbook ─────────────────────────────────────────────────────────────

    suspend fun getClassbookEntries(): Result<List<ClassbookEntry>> {
        return try {
            val start = LocalDate.now().minusDays(30).toUntis()
            val end   = LocalDate.now().toUntis()
            val session = sessionManager.session
                ?: return Result.failure(Exception("Nicht angemeldet"))
            val studentId = cachedClassElementId
            val response = when {
                session.personType == 12 && studentId != null ->
                    service().getClassbookEntriesForParent(start, end, studentId)
                else -> {
                    val r = service().getClassbookEntriesForStudent(start, end)
                    if (r.isSuccessful) r else service().getClassbookEntries(start, end)
                }
            }
            val raw = rawBody(response) ?: return Result.success(emptyList())
            val entries: List<ClassbookEntry> = try {
                val root = JsonParser.parseString(raw).asJsonObject
                val dataEl = root.get("data")
                when {
                    dataEl == null -> emptyList()
                    dataEl.isJsonArray ->
                        parseJson(dataEl.toString(), object : TypeToken<List<ClassbookEntry>>() {})
                    dataEl.isJsonObject -> {
                        val obj = dataEl.asJsonObject
                        when {
                            obj.has("rows") -> {
                                val rows = parseJson(obj.get("rows").toString(),
                                    object : TypeToken<List<ClassbookRow>>() {})
                                rows.map { it.toClassbookEntry() }
                            }
                            obj.has("classRegEntries") -> {
                                val inner = obj.get("classRegEntries")
                                parseJson(inner.toString(), object : TypeToken<List<ClassbookEntry>>() {})
                            }
                            else -> emptyList()
                        }
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) { emptyList() }
            Result.success(entries.sortedByDescending { it.date ?: 0 })
        } catch (e: Exception) { Result.failure(Exception("Klassenbuch: ${e.message}", e)) }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    suspend fun getEvents(): Result<List<SchoolEvent>> {
        return try {
            val classId = cachedClassElementId ?: 0
            if (classId == 0) return Result.success(emptyList())
            val start = LocalDate.now()
            val end   = start.plusDays(90)
            val resp = callTimetableV1(start.toIso(), end.toIso(), classId)
            if (!resp.isSuccessful) return Result.success(emptyList())
            val raw = resp.body()?.string()?.trim() ?: return Result.success(emptyList())
            if (raw.isBlank() || raw.contains("<html", ignoreCase = true)) return Result.success(emptyList())
            val ttResp: TimetableV1Response = parseJson(raw)
            val events = mutableListOf<SchoolEvent>()
            ttResp.days?.forEach { day ->
                val dateStr = day.date ?: return@forEach
                val dateInt = dateStr.replace("-", "").toIntOrNull() ?: return@forEach
                day.gridEntries?.forEach { entry ->
                    val isExam = entry.type == "EXAM"
                    val hasExamInfo = !entry.lessonInfo.isNullOrBlank() &&
                        entry.lessonInfo.contains(Regex("KA|Test|Klassenarbeit|Überpr|Arbeit", RegexOption.IGNORE_CASE))
                    if (isExam || hasExamInfo) {
                        val startT = entry.duration?.start?.drop(11)?.take(5)?.replace(":", "")?.toIntOrNull() ?: 0
                        val endT   = entry.duration?.end?.drop(11)?.take(5)?.replace(":", "")?.toIntOrNull() ?: 0
                        val allPos = listOfNotNull(entry.position1, entry.position2, entry.position3, entry.position4)
                            .flatten().mapNotNull { it.current }
                        val subject = allPos.firstOrNull { it.type == "SUBJECT" }
                        events.add(SchoolEvent(
                            id = entry.ids?.firstOrNull() ?: 0,
                            subject = subject?.shortName,
                            title = entry.lessonInfo?.takeIf { it.isNotBlank() }
                                ?: "${subject?.longName ?: subject?.shortName ?: "Arbeit"}",
                            text = entry.lessonInfo, remark = entry.substitutionText?.takeIf { it.isNotBlank() },
                            date = dateInt, startTime = startT, endTime = endT,
                            eventType = if (isExam) "EXAM" else "TEST",
                            examType  = if (isExam) "EXAM" else "TEST",
                            isExam = true
                        ))
                    }
                }
            }
            Result.success(events.sortedBy { it.date ?: 0 })
        } catch (e: Exception) {
            Result.failure(Exception("Termine: ${e.message}", e))
        }
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    suspend fun getMessages(): Result<List<Message>> {
        return try {
            val token = bearerToken ?: fetchBearerToken()
            val resp = if (token != null) {
                service().getMessagesAuth("Bearer $token")
            } else {
                service().getMessages()
            }
            // Retry once with a fresh token on 401/403
            val effectiveResp = if (resp.code() in listOf(401, 403)) {
                bearerToken = null
                val fresh = fetchBearerToken()
                if (fresh != null) service().getMessagesAuth("Bearer $fresh")
                else service().getMessages()
            } else resp
            val raw = rawBody(effectiveResp) ?: return Result.success(emptyList())
            val msgsResp: MessagesResponse = parseJson(raw)
            Result.success(
                (msgsResp.incomingMessages ?: emptyList())
                    .sortedByDescending { it.sentDateTime }
            )
        } catch (e: Exception) {
            Result.failure(Exception("Nachrichten: ${e.message}", e))
        }
    }

    suspend fun getUnreadMessageCount(): Int {
        return try {
            val resp = service().getMessagesStatus()
            if (!resp.isSuccessful) return 0
            val raw = resp.body()?.string()?.trim() ?: return 0
            parseJson<MessagesStatusResponse>(raw).unreadMessagesCount
        } catch (_: Exception) { 0 }
    }

    // ── Absences ──────────────────────────────────────────────────────────────

    suspend fun getAbsences(): Result<List<Absence>> {
        return try {
            val studentId = cachedClassElementId ?: 0
            if (studentId == 0) return Result.success(emptyList())
            val today = LocalDate.now()
            val yearStart = if (today.monthValue >= 8) today.year else today.year - 1
            val startDate = "${yearStart}0801"
            val endDate   = "${yearStart + 1}0731"
            val resp = service().getAbsences(startDate, endDate, studentId)
            val raw = rawBody(resp) ?: return Result.success(emptyList())
            val absResp: AbsencesResponse = parseJson(raw)
            Result.success(
                (absResp.data?.absences ?: emptyList())
                    .sortedByDescending { it.startDate ?: 0 }
            )
        } catch (e: Exception) {
            Result.failure(Exception("Abwesenheiten: ${e.message}", e))
        }
    }
}

data class TimetableDay(val date: LocalDate, val lessons: List<Lesson>) {
    val isToday: Boolean    get() = date == LocalDate.now()
    val isTomorrow: Boolean get() = date == LocalDate.now().plusDays(1)
    val label: String get() = when {
        isToday    -> "Heute"
        isTomorrow -> "Morgen"
        else       -> date.format(DateTimeFormatter.ofPattern("EEE dd.MM.", java.util.Locale.GERMAN))
    }
}
