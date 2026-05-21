package com.webuntis.dashboard.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.webuntis.dashboard.model.*
import kotlinx.coroutines.async
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
            // The JSON-RPC call already established a JSESSIONID cookie on the server.
            // We must carry it over to the REST login; on Android 15 the CookieJar
            // does this automatically only if we wait one frame — but since session is
            // still null the injector skips it. Force a short yield so the CookieJar
            // has flushed the Set-Cookie headers from the RPC response before the
            // REST call reads loadForRequest.
            kotlinx.coroutines.yield()
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
                val lessons = ttResp.toLessons()
                val enriched = enrichLessonsWithDetail(lessons, classId, session.personType)
                Result.success(enriched)
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
        val numDays = sessionManager.timetableDays
        val today = LocalDate.now()
        // Fetch enough data: worst case every day is a weekend/holiday, so request
        // 3× the desired days to guarantee we can fill numDays school days.
        val fetchDays = (numDays * 3).coerceAtLeast(21).toLong()
        val rangeResult = fetchLessonsInRange(today.toUntis(), today.plusDays(fetchDays).toUntis())
        if (rangeResult.isFailure) return Result.failure(rangeResult.exceptionOrNull()!!)
        val byDate = rangeResult.getOrThrow()
            .groupBy { it.date }.entries
            .filter { (d, _) -> !untisIntToDate(d).isBefore(today) && untisIntToDate(d).dayOfWeek.value <= 5 }
            .sortedBy { it.key }.take(numDays)
            .map { (d, lessons) -> TimetableDay(untisIntToDate(d), lessons.sortedBy { it.startTime }) }
        return Result.success(byDate)
    }

    private fun untisIntToDate(d: Int): LocalDate {
        val s = d.toString().padStart(8, '0')
        return LocalDate.of(s.substring(0,4).toInt(), s.substring(4,6).toInt(), s.substring(6,8).toInt())
    }

    // ── Calendar Entry Detail enrichment ──────────────────────────────────────

    /**
     * For lessons that are substitutions or have no info text, fetch detail from v2 API
     * and fill in substText / lessonInfo / notesAll.
     * Uses parallel coroutines, max 8 concurrent to avoid flooding the server.
     */
    private suspend fun enrichLessonsWithDetail(
        lessons: List<Lesson>,
        elementId: Int,
        elementType: Int
    ): List<Lesson> {
        val token = bearerToken ?: fetchBearerToken() ?: return lessons
        // Enrich all lessons — teaching content, teacher substitution status,
        // and notes are only available via the v2 detail endpoint regardless of lesson type.
        val needsDetail = lessons

        val detailMap = mutableMapOf<Int, CalendarEntryDetail>()

        kotlinx.coroutines.coroutineScope {
            needsDetail.chunked(8).forEach { batch ->
                val jobs = batch.map { lesson ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val dateStr = lesson.date.toString()
                            val dateIso = "${dateStr.substring(0,4)}-${dateStr.substring(4,6)}-${dateStr.substring(6,8)}"
                            val startHH = lesson.startTime / 100
                            val startMM = lesson.startTime % 100
                            val endHH   = lesson.endTime   / 100
                            val endMM   = lesson.endTime   % 100
                            val startDt = "${dateIso}T${startHH.toString().padStart(2,'0')}:${startMM.toString().padStart(2,'0')}:00"
                            val endDt   = "${dateIso}T${endHH.toString().padStart(2,'0')}:${endMM.toString().padStart(2,'0')}:00"
                            val resp = service().getCalendarEntryDetail(
                                authorization = "Bearer $token",
                                elementId     = elementId,
                                elementType   = elementType,
                                startDateTime = startDt,
                                endDateTime   = endDt
                            )
                            val raw = rawBody(resp) ?: return@async
                            val detail: CalendarEntryDetailResponse = parseJson(raw)
                            detail.calendarEntries?.firstOrNull()?.let { entry ->
                                synchronized(detailMap) { detailMap[lesson.id] = entry }
                            }
                        } catch (_: Exception) {}
                    }
                }
                jobs.forEach { it.await() }
            }
        }

        return lessons.map { lesson ->
            val detail = detailMap[lesson.id] ?: return@map lesson

            // substText: keep existing lesson value, fall back to detail
            val newSubst = lesson.substText?.takeIf { it.isNotBlank() }
                ?: detail.substText?.takeIf { it.isNotBlank() }

            // info: prefer lessonInfo, then notesAll, then notesStaff
            val newInfo = lesson.info?.takeIf { it.isNotBlank() }
                ?: detail.lessonInfo?.takeIf { it.isNotBlank() }
                ?: detail.notesAll?.takeIf { it.isNotBlank() }
                ?: detail.notesStaff?.takeIf { it.isNotBlank() }

            // teachingContent: what was actually taught this lesson (e.g. "AH Seite 78-82")
            val newTeachingContent = detail.teachingContent?.takeIf { it.isNotBlank() }

            // Teacher substitution info from detail (status: REMOVED / SUBSTITUTION)
            val removed      = detail.removedTeachers.takeIf { it.isNotEmpty() }
            val substituted  = detail.substitutedTeachers.takeIf { it.isNotEmpty() }

            // Override lesson code/lstype if detail says it's cancelled or substitution
            // and the JSON-RPC response didn't already mark it
            val newCode   = when {
                lesson.code != null          -> lesson.code
                detail.isCancelled           -> "cancelled"
                detail.isSubstitution        -> "irregular"
                else                         -> null
            }
            val newLstype = when {
                lesson.lstype != null        -> lesson.lstype
                detail.isCancelled           -> "cancel"
                detail.isSubstitution        -> "subst"
                else                         -> null
            }

            lesson.copy(
                substText           = newSubst,
                info                = newInfo,
                teachingContent     = newTeachingContent,
                removedTeachers     = removed,
                substitutedTeachers = substituted,
                code                = newCode,
                lstype              = newLstype
                // notesForAll is already set from v1 gridEntry; don't overwrite it here
            )
        }
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

    @Volatile private var secondBearerToken: String? = null

    private suspend fun fetchMessagesWithToken(token: String?, label: String): List<Message> {
        val resp = if (token != null) service().getMessagesAuth("Bearer $token")
                   else service().getMessages()
        val effective = if (resp.code() in listOf(401, 403)) {
            bearerToken = null
            val fresh = fetchBearerToken()
            if (fresh != null) service().getMessagesAuth("Bearer $fresh")
            else service().getMessages()
        } else resp
        val raw = rawBody(effective) ?: return emptyList()
        val parsed: MessagesResponse = parseJson(raw)
        return (parsed.incomingMessages ?: emptyList())
            .map { it.copy(accountLabel = label) }
    }

    private suspend fun fetchMessagesForSecondAccount(
        server: String, schoolname: String,
        username: String, password: String, label: String
    ): List<Message> {
        return try {
            val svc = retrofitFactory.create(server)
            val body = JsonRpcRequest(
                method = "authenticate",
                params = mapOf("user" to username, "password" to password, "client" to "android")
            )
            val loginResp = svc.jsonRpcLogin(schoolname, body)
            val loginRaw  = loginResp.body()?.string()?.trim() ?: return emptyList()
            val rpcResp: JsonRpcResponse<AuthResult> =
                parseJson(loginRaw, object : TypeToken<JsonRpcResponse<AuthResult>>() {})
            val authResult = rpcResp.result ?: return emptyList()

            val resolvedLabel = label.ifBlank {
                authResult.personName?.takeIf { it.isNotBlank() } ?: username
            }
            sessionManager.secondAccount?.let { existing ->
                sessionManager.secondAccount = existing.copy(
                    personType = authResult.personType ?: 0,
                    personName = authResult.personName ?: "",
                    label = resolvedLabel
                )
            }

            val bearerResp = svc.getBearerToken()
            val bearerRaw  = bearerResp.body()?.string()?.trim() ?: return emptyList()
            val token = if (bearerRaw.startsWith("{"))
                com.google.gson.JsonParser.parseString(bearerRaw).asJsonObject.get("token")?.asString ?: bearerRaw
            else bearerRaw.trim('"')
            secondBearerToken = token

            val msgResp = svc.getMessagesAuth("Bearer $token")
            val raw     = rawBody(msgResp) ?: return emptyList()
            val parsed: MessagesResponse = parseJson(raw)
            (parsed.incomingMessages ?: emptyList())
                .map { it.copy(accountLabel = resolvedLabel) }
        } catch (e: Exception) { emptyList() }
    }

    /** Returns the bearer token that owns this message. */
    private fun tokenForMessage(msg: Message): String? {
        val secondLabel = sessionManager.secondAccount?.label ?: return bearerToken
        return if (!msg.accountLabel.isNullOrBlank() && msg.accountLabel == secondLabel)
            secondBearerToken ?: bearerToken
        else bearerToken
    }

    suspend fun verifyAndSaveSecondAccount(
        username: String, password: String, label: String
    ): Result<String> {
        return try {
            val session = sessionManager.session
                ?: return Result.failure(Exception("Nicht angemeldet"))
            val svc  = retrofitFactory.create(session.server)
            val body = JsonRpcRequest(
                method = "authenticate",
                params = mapOf("user" to username, "password" to password, "client" to "android")
            )
            val resp    = svc.jsonRpcLogin(session.schoolname, body)
            val raw     = resp.body()?.string()?.trim()
                ?: return Result.failure(Exception("Keine Antwort vom Server"))
            val rpcResp: JsonRpcResponse<AuthResult> =
                parseJson(raw, object : TypeToken<JsonRpcResponse<AuthResult>>() {})
            if (rpcResp.error != null)
                return Result.failure(Exception(rpcResp.error.message ?: "Login fehlgeschlagen"))
            val auth = rpcResp.result ?: return Result.failure(Exception("Keine Antwort"))

            val resolvedName  = auth.personName?.takeIf { it.isNotBlank() } ?: username
            val resolvedLabel = label.ifBlank { resolvedName }
            val typeLabel = when (auth.personType) {
                2 -> "Lehrer"; 5 -> "Schüler"; 12 -> "Eltern"
                else -> "Unbekannt (Typ ${auth.personType})"
            }
            sessionManager.secondAccount = SessionManager.SecondAccount(
                username = username, password = password, label = resolvedLabel,
                personType = auth.personType ?: 0, personName = resolvedName
            )
            Result.success("$resolvedName · $typeLabel")
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getMessages(): Result<List<Message>> {
        return try {
            val session      = sessionManager.session
            val primaryLabel = session?.personName?.takeIf { it.isNotBlank() }
                ?: session?.accountTypeLabel ?: "Hauptaccount"

            val token   = bearerToken ?: fetchBearerToken()
            val primary = fetchMessagesWithToken(token, primaryLabel)
            val second  = sessionManager.secondAccount?.let { acc ->
                val server = session?.server ?: return@let emptyList()
                fetchMessagesForSecondAccount(server, session.schoolname,
                    acc.username, acc.password, acc.label)
            } ?: emptyList()

            val seenIds  = mutableSetOf<Int>()
            val seenKeys = mutableSetOf<String>()
            val merged   = (primary + second)
                .sortedByDescending { it.sentDateTime }
                .filter { msg ->
                    seenIds.add(msg.id) &&
                    seenKeys.add("${msg.subject}|${msg.sender?.userId}|${msg.sentDateTime}")
                }
            Result.success(merged)
        } catch (e: Exception) {
            Result.failure(Exception("Nachrichten: ${e.message}", e))
        }
    }

    /** Fetch storageAttachments using the correct account token. */
    suspend fun getMessageWithAttachments(msg: Message): Result<Message> {
        return try {
            val token = tokenForMessage(msg) ?: return Result.success(msg)
            val resp  = service().getMessageDetail(msg.id, "Bearer $token")
            val raw   = rawBody(resp) ?: return Result.success(msg)
            val obj   = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val atts  = obj.getAsJsonArray("storageAttachments")
                ?.mapNotNull { el ->
                    val o = el.asJsonObject
                    Attachment(
                        id   = o.get("id")?.takeIf   { !it.isJsonNull }?.asString,
                        name = o.get("name")?.takeIf { !it.isJsonNull }?.asString
                    )
                } ?: emptyList()
            Result.success(msg.copy(attachmentList = atts))
        } catch (e: Exception) { Result.success(msg) }
    }

    /**
     * Download a storageAttachment:
     * 1. GET /{uuid}/attachmentstorageurl  → presigned S3 URL + encryption headers
     * 2. GET the S3 URL with those headers
     */
    suspend fun downloadAttachment(attachmentId: String, msg: Message): Result<ByteArray> {
        return try {
            val token = tokenForMessage(msg)
                ?: return Result.failure(Exception("Nicht angemeldet"))

            // Step 1: get presigned URL + encryption headers
            val urlResp = service().getAttachmentStorageUrl(attachmentId, "Bearer $token")
            val urlRaw  = rawBody(urlResp)
                ?: return Result.failure(Exception("Keine Download-URL"))
            val storageUrl: AttachmentStorageUrl = parseJson(urlRaw)

            val downloadUrl = storageUrl.downloadUrl
                ?: return Result.failure(Exception("Keine URL in Antwort"))

            val headers   = storageUrl.additionalHeaders?.associate { it.key!! to it.value!! } ?: emptyMap()
            val algorithm = headers["x-amz-server-side-encryption-customer-algorithm"] ?: ""
            val encKey    = headers["x-amz-server-side-encryption-customer-key"] ?: ""
            val encKeyMd5 = headers["x-amz-server-side-encryption-customer-key-md5"] ?: ""

            // Step 2: download from S3
            val dlResp = service().downloadFromStorage(downloadUrl, algorithm, encKey, encKeyMd5)
            val bytes  = dlResp.body()?.bytes()
                ?: return Result.failure(Exception("Leere Antwort vom Speicher"))
            Result.success(bytes)
        } catch (e: Exception) { Result.failure(e) }
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
