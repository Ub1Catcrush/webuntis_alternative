package com.webuntis.dashboard.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.webuntis.dashboard.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.StringReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton

/** Thrown when the server rejects a request due to an expired/invalid session. */
class SessionExpiredException(message: String = "Session abgelaufen") : Exception(message)

@Singleton
class WebUntisRepository @Inject constructor(
    private val retrofitFactory: RetrofitFactory,
    internal val sessionManager: SessionManager
) {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val isoFmt  = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val _bearerToken = AtomicReference<String?>(null)
    private var bearerToken: String?
        get() = _bearerToken.get()
        set(v) { _bearerToken.set(v) }
    private val loginMutex = Mutex()

    private suspend fun getAuthHeader(): String? {
        val token = bearerToken ?: fetchBearerToken().getOrNull()
        return token?.let { "Bearer $it" }
    }

    private suspend fun reAuthSilently(
        server: String, schoolname: String, username: String, password: String
    ): Result<SessionData> {
        val rpc = loginViaJsonRpc(server, schoolname, username, password)
        val result = if (rpc.isSuccess) { rpc } else {
            kotlinx.coroutines.yield()
            loginViaRest(server, schoolname, username, password)
        }
        if (result.isSuccess) {
            sessionManager.storedCredentials = Pair(username, password)
            fetchBearerToken()
        }
        return result
    }

    private suspend fun reLoginIfNeeded() {
        if (sessionManager.isSessionFresh()) return
        val creds   = sessionManager.storedCredentials ?: return
        val session = sessionManager.session ?: return
        loginMutex.withLock {
            if (sessionManager.isSessionFresh()) return@withLock
            android.util.Log.i("WebUntis", "Session stale — re-authenticating silently")
            reAuthSilently(session.server, session.schoolname, creds.first, creds.second)
        }
    }

    private suspend fun <T> withCacheOrFetch(
        forceRefresh: Boolean = false,
        cache: () -> CacheEntry<T>?,
        store: (CacheEntry<T>) -> Unit,
        block: suspend () -> Result<T>
    ): Result<T> {
        val entry = cache()
        if (!forceRefresh && entry != null && sessionManager.isCacheFresh(entry.fetchedAt)) {
            return entry.data
        }
        val result = withSessionRetry(block)
        store(CacheEntry(System.currentTimeMillis(), result))
        return result
    }

    private suspend fun <T> withSessionRetry(block: suspend () -> Result<T>): Result<T> {
        return try {
            reLoginIfNeeded()
            val result = block()
            if (result.isSuccess) sessionManager.touchSession()
            result
        } catch (e: SessionExpiredException) {
            android.util.Log.w("WebUntis", "Session expired mid-request — retrying after re-login")
            val creds   = sessionManager.storedCredentials
            val session = sessionManager.session
            if (creds == null || session == null) return Result.failure(e)
            if (reAuthSilently(session.server, session.schoolname, creds.first, creds.second).isFailure)
                return Result.failure(e)
            try {
                val retry = block()
                if (retry.isSuccess) sessionManager.touchSession()
                retry
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    private data class CacheEntry<T>(val fetchedAt: Long, val data: Result<T>)

    private var cacheTimetable:  CacheEntry<List<TimetableDay>>?                        = null
    private var cacheHomework:   CacheEntry<Pair<List<Homework>, Map<String, String>>>? = null
    private var cacheEvents:     CacheEntry<List<SchoolEvent>>?                         = null
    private var cacheClassbook:  CacheEntry<List<ClassbookEntry>>?                      = null
    private var cacheAbsences:   CacheEntry<List<Absence>>?                             = null
    private var cacheMessages:   CacheEntry<List<Message>>?                             = null
    private var cacheAbsencesMeta: CacheEntry<AbsencesMetaData>?                        = null

    fun clearAllCaches() {
        cacheTimetable = null; cacheHomework = null; cacheEvents = null
        cacheClassbook = null; cacheAbsences = null; cacheMessages = null
        cacheAbsencesMeta = null
    }

    fun isHomeworkCacheFresh():  Boolean { val e = cacheHomework  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isEventsCacheFresh():    Boolean { val e = cacheEvents    ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isClassbookCacheFresh(): Boolean { val e = cacheClassbook ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isAbsencesCacheFresh():  Boolean { val e = cacheAbsences  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isMessagesCacheFresh():  Boolean { val e = cacheMessages  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isTimetableCacheFresh(): Boolean {
        val entry = cacheTimetable ?: return false
        return sessionManager.isCacheFresh(entry.fetchedAt)
    }

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
        if (r.headers()["X-WebUntis-Session-Expired"] == "true") throw SessionExpiredException()
        if (!r.isSuccessful) {
            val msg = tryExtractMessage(r.errorBody()?.string() ?: "") ?: "HTTP ${r.code()}"
            throw Exception(msg)
        }
        val raw = r.body()?.string()?.trim() ?: return null
        if (raw.contains("<html", ignoreCase = true)) throw SessionExpiredException()
        if (raw.contains("-32001")) throw SessionExpiredException()
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

    private suspend fun fetchBearerToken(): Result<String?> {
        return try {
            val resp = service().getBearerToken()
            if (!resp.isSuccessful) return Result.success(null)
            val raw = resp.body()?.string()?.trim() ?: return Result.success(null)
            val token = if (raw.startsWith("{")) {
                JsonParser.parseString(raw).asJsonObject.get("token")?.asString ?: raw
            } else raw.trim('"')
            bearerToken = token
            Result.success(token)
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun callTimetableV1(
        startIso: String, endIso: String, classId: Int
    ): retrofit2.Response<okhttp3.ResponseBody> {
        val token = getAuthHeader()
        val resp = if (token != null) {
            service().getTimetableV1Auth(
                authorization = token,
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
            bearerToken = null
            val fresh = getAuthHeader() ?: return resp
            return service().getTimetableV1Auth(
                authorization = fresh,
                start = startIso, end = endIso,
                resourceType = "STUDENT", resources = classId.toString()
            )
        }
        return resp
    }

    suspend fun login(
        server: String, schoolname: String, username: String, password: String
    ): Result<SessionData> = loginMutex.withLock {
        val rpc = loginViaJsonRpc(server, schoolname, username, password)
        val result = if (rpc.isSuccess) {
            sessionManager.storedCredentials = Pair(username, password)
            rpc
        } else {
            kotlinx.coroutines.yield()
            val rest = loginViaRest(server, schoolname, username, password)
            if (rest.isSuccess) sessionManager.storedCredentials = Pair(username, password)
            rest
        }
        if (result.isSuccess) {
            val session = result.getOrNull()
            if (session != null && sessionManager.studentId == 0) {
                val id = when {
                    session.classId  > 0 -> session.classId
                    session.personId > 0 -> session.personId
                    else                 -> 0
                }
                if (id != 0) sessionManager.studentId = id
            }
            fetchBearerToken()
        }
        result
    }

    suspend fun primeCachedElementIdIfNeeded() {
        val session = sessionManager.session ?: return
        if (session.personType != 12) return
        if (sessionManager.studentId != 0) return
        getHomework(forceRefresh = true)
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
        bearerToken = null
        clearAllCaches()
        sessionManager.clearAll()
    }

    // ── Timetable ─────────────────────────────────────────────────────────────

    private suspend fun fetchLessonsInRange(
        startDate: String, endDate: String
    ): Result<List<Lesson>> {
        val session = sessionManager.session
            ?: return Result.failure(Exception("Nicht angemeldet"))

        val effectiveClassId = sessionManager.studentId
        if (effectiveClassId != 0) {
            val startIso = "${startDate.substring(0,4)}-${startDate.substring(4,6)}-${startDate.substring(6,8)}"
            val endIso   = "${endDate.substring(0,4)}-${endDate.substring(4,6)}-${endDate.substring(6,8)}"
            return try {
                val response = callTimetableV1(startIso, endIso, effectiveClassId)
                if (!response.isSuccessful)
                    return Result.failure(Exception("Stundenplan HTTP ${response.code()}"))
                val raw = response.body()?.string()?.trim()
                    ?: return Result.failure(Exception("Leere Antwort"))
                if (raw.contains("<html", ignoreCase = true))
                    throw SessionExpiredException()
                val ttResp: TimetableV1Response = parseJson(raw)
                val lessons = ttResp.toLessons()
                val enriched = enrichLessonsWithDetail(lessons, effectiveClassId, 5)
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

    suspend fun getTwoSchoolDays(forceRefresh: Boolean = false): Result<List<TimetableDay>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheTimetable },
        store = { cacheTimetable = it },
    ) {
        val numDays = sessionManager.timetableDays
        val today = LocalDate.now()
        val fetchDays = (numDays * 3).coerceAtLeast(21).toLong()
        val rangeResult = fetchLessonsInRange(today.toUntis(), today.plusDays(fetchDays).toUntis())
        if (rangeResult.isFailure) return@withCacheOrFetch Result.failure(rangeResult.exceptionOrNull()!!)
        val byDate = rangeResult.getOrThrow()
            .groupBy { it.date }.entries
            .filter { (d, _) -> !untisIntToDate(d).isBefore(today) && untisIntToDate(d).dayOfWeek.value <= 5 }
            .sortedBy { it.key }.take(numDays)
            .map { (d, lessons) -> 
                val merged = mergeOverlappingLessons(lessons)
                TimetableDay(untisIntToDate(d), merged.sortedBy { it.startTime }) 
            }
        Result.success(byDate)
    }

    private fun untisIntToDate(d: Int): LocalDate {
        val s = d.toString().padStart(8, '0')
        return LocalDate.of(s.substring(0,4).toInt(), s.substring(4,6).toInt(), s.substring(6,8).toInt())
    }

    /**
     * Identifies lessons at the same time and attempts to logically merge them.
     * If one lesson is cancelled and another is active, the active one is treated
     * as a substitution for the cancelled one and the cancelled one is hidden.
     * Also transfers teacher names to show who is being substituted.
     */
    private fun mergeOverlappingLessons(lessons: List<Lesson>): List<Lesson> {
        if (lessons.size < 2) return lessons
        
        val result = mutableListOf<Lesson>()
        val cancelledPool = lessons.filter { it.isCancelled }.toMutableList()
        val active = lessons.filter { !it.isCancelled }

        active.forEach { a ->
            // Find ALL cancelled lessons that overlap significantly with this active one.
            val overlapping = cancelledPool.filter { c ->
                maxOf(a.startTime, c.startTime) < minOf(a.endTime, c.endTime)
            }
            
            if (overlapping.isNotEmpty()) {
                val insteadOf = overlapping.map { it.subjectName }.distinct().joinToString(", ")
                
                // Collect teacher names from the cancelled lessons being replaced
                val replacedTeachers = overlapping.flatMap { c ->
                    c.te?.mapNotNull { it.longname ?: it.name } ?: emptyList<String>()
                }.distinct()
                
                // Combine with existing removed teachers
                val combinedRemoved = ((a.removedTeachers ?: emptyList<String>()) + replacedTeachers).distinct()
                
                // Mark as substitution and set replaced subject
                val newLstype = if (a.lstype == null || a.lstype == "ls") "subst" else a.lstype
                
                result.add(a.copy(
                    replacedSubject = insteadOf, 
                    lstype = newLstype,
                    removedTeachers = combinedRemoved.ifEmpty { null }
                ))
                
                // REMOVE from pool so they are not shown separately
                val overlappingIds = overlapping.map { it.id }.toSet()
                cancelledPool.removeAll { it.id in overlappingIds }
            } else {
                result.add(a)
            }
        }
        
        // Add remaining cancelled lessons that weren't replaced by an active one
        result.addAll(cancelledPool)
        
        return result.sortedBy { it.startTime }
    }

    // ── Calendar Entry Detail enrichment ──────────────────────────────────────

    private suspend fun enrichLessonsWithDetail(
        lessons: List<Lesson>,
        elementId: Int,
        elementType: Int
    ): List<Lesson> {
        val token = getAuthHeader() ?: return lessons
        val needsDetail = lessons.take(40)

        val detailMap = mutableMapOf<Int, CalendarEntryDetail>()
        val semaphore = Semaphore(12)

        coroutineScope {
            needsDetail.map { lesson ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
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
                                authorization = token,
                                elementId     = elementId,
                                elementType   = elementType,
                                startDateTime = startDt,
                                endDateTime   = endDt
                            )
                            val raw = rawBody(resp) ?: return@withPermit
                            val detail: CalendarEntryDetailResponse = parseJson(raw)
                            detail.calendarEntries?.firstOrNull()?.let { entry ->
                                synchronized(detailMap) { detailMap[lesson.id] = entry }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }.awaitAll()
        }

        return lessons.map { lesson ->
            val detail = detailMap[lesson.id] ?: return@map lesson
            val newSubst = lesson.substText?.takeIf { it.isNotBlank() }
                ?: detail.substText?.takeIf { it.isNotBlank() }
            val newInfo = lesson.info?.takeIf { it.isNotBlank() }
                ?: detail.lessonInfo?.takeIf { it.isNotBlank() }
                ?: detail.notesAll?.takeIf { it.isNotBlank() }
                ?: detail.notesStaff?.takeIf { it.isNotBlank() }
            val newTeachingContent = detail.teachingContent?.takeIf { it.isNotBlank() }
            val removed      = detail.removedTeachers.takeIf { it.isNotEmpty() }
            val substituted  = detail.substitutedTeachers.takeIf { it.isNotEmpty() }
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
            
            val mergedRemoved = ((lesson.removedTeachers ?: emptyList<String>()) + (removed ?: emptyList<String>())).distinct().ifEmpty { null }
            
            lesson.copy(
                substText           = newSubst,
                info                = newInfo,
                teachingContent     = newTeachingContent,
                removedTeachers     = mergedRemoved,
                substitutedTeachers = substituted,
                code                = newCode,
                lstype              = newLstype
            )
        }
    }

    // ── Homework ──────────────────────────────────────────────────────────────

    suspend fun getHomework(forceRefresh: Boolean = false): Result<Pair<List<Homework>, Map<String, String>>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheHomework },
        store = { cacheHomework = it },
    ) {
        try {
            val token = getAuthHeader()
            val start = LocalDate.now().minusDays(14).toUntis()
            val end   = LocalDate.now().plusDays(21).toUntis()
            val response = service().getHomework(token, start, end)
            val raw = rawBody(response) ?: return@withCacheOrFetch Result.success(Pair(emptyList<Homework>(), emptyMap<String, String>()))
            val hwResp: HomeworkResponse = parseJson(raw)
            hwResp.data?.records?.firstOrNull()?.elementIds?.firstOrNull()?.let {
                if (it != 0) sessionManager.studentId = it
            }
            val lessonMap = hwResp.data?.lessons
                ?.filter { it.id != null && !it.subject.isNullOrBlank() }
                ?.associate { it.id.toString() to (it.subject ?: "") }
                ?: emptyMap()
            val rawHomeworks = hwResp.data?.homeworks ?: emptyList()

            val semaphore = Semaphore(5)
            val enriched = coroutineScope {
                rawHomeworks.map { hw ->
                    async(Dispatchers.IO) {
                        if (hw.attachments.isNullOrEmpty()) return@async hw
                        semaphore.withPermit {
                            try {
                                val attResp = service().getHomeworkAttachments(token, hw.id)
                                if (attResp.isSuccessful) {
                                    val attRaw = attResp.body()?.string()?.trim()
                                    if (!attRaw.isNullOrBlank() && attRaw.startsWith("[")) {
                                        val atts: List<HomeworkAttachment> = parseJson(attRaw)
                                        hw.copy(attachments = atts.ifEmpty { null })
                                    } else hw
                                } else hw
                            } catch (_: Exception) { hw }
                        }
                    }
                }.awaitAll()
            }
            Result.success(Pair(enriched, lessonMap))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Classbook ─────────────────────────────────────────────────────────────

    suspend fun getClassbookEntries(forceRefresh: Boolean = false): Result<List<ClassbookEntry>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheClassbook },
        store = { cacheClassbook = it },
    ) {
        try {
            val token = getAuthHeader()
            val start = LocalDate.now().minusDays(30).toUntis()
            val end   = LocalDate.now().toUntis()
            val session = sessionManager.session
                ?: return@withCacheOrFetch Result.failure(Exception("Nicht angemeldet"))
            if (session.personType == 12 && sessionManager.studentId == 0) {
                getHomework(forceRefresh = false)
            }
            val studentId = sessionManager.studentId
            val response = when {
                session.personType == 12 && studentId != 0 ->
                    service().getClassbookEntriesForParent(token, start, end, studentId)
                else -> {
                    val r = service().getClassbookEntriesForStudent(token, start, end)
                    if (r.isSuccessful) r else service().getClassbookEntries(token, start, end)
                }
            }
            val raw = rawBody(response) ?: return@withCacheOrFetch Result.success(emptyList<ClassbookEntry>())
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
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    suspend fun getEvents(forceRefresh: Boolean = false): Result<List<SchoolEvent>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheEvents },
        store = { cacheEvents = it },
    ) {
        try {
            val token = getAuthHeader()
            val eventSession = sessionManager.session
            if (eventSession?.personType == 12 && sessionManager.studentId == 0) {
                getHomework(forceRefresh = false)
            }
            val classId = sessionManager.studentId
            if (classId == 0) return@withCacheOrFetch Result.success(emptyList<SchoolEvent>())
            val start = LocalDate.now()
            val end   = start.plusDays(90)
            val resp = callTimetableV1(start.toIso(), end.toIso(), classId)
            if (!resp.isSuccessful) return@withCacheOrFetch Result.success(emptyList<SchoolEvent>())
            val raw = resp.body()?.string()?.trim() ?: return@withCacheOrFetch Result.success(emptyList<SchoolEvent>())
            if (raw.isBlank() || raw.contains("<html", ignoreCase = true)) return@withCacheOrFetch Result.success(emptyList<SchoolEvent>())
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
                        val startT = startIsoToTimeInt(entry.duration?.start)
                        val endT   = startIsoToTimeInt(entry.duration?.end)
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
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun startIsoToTimeInt(iso: String?): Int {
        if (iso == null || iso.length < 16) return 0
        return iso.substring(11, 16).replace(":", "").toIntOrNull() ?: 0
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    @Volatile private var secondBearerToken: String? = null

    private suspend fun fetchMessagesWithToken(token: String?, label: String): Result<List<Message>> {
        return try {
            val resp = service().getMessagesAuth(token ?: "")
            val effective = if (resp.code() in listOf(401, 403)) {
                bearerToken = null
                val fresh = getAuthHeader()
                if (fresh != null) service().getMessagesAuth(fresh)
                else resp
            } else resp
            val raw = rawBody(effective) ?: Result.success(emptyList<Message>()).getOrThrow().let { "" } // Should not happen with rawBody logic
            // Re-evaluating rawBody usage. rawBody throws on HTML/error.
            val rawActual = try { rawBody(effective) ?: "" } catch(e: Exception) { throw e }
            if (rawActual.isEmpty()) return Result.success(emptyList())

            val parsed: MessagesResponse = parseJson(rawActual)
            Result.success((parsed.incomingMessages ?: emptyList()).map { it.copy(accountLabel = label) })
        } catch (e: Exception) { Result.failure(e) }
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

    suspend fun getMessages(forceRefresh: Boolean = false): Result<List<Message>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheMessages },
        store = { cacheMessages = it },
    ) {
        try {
            val session      = sessionManager.session
            val primaryLabel = session?.personName?.takeIf { it.isNotBlank() }
                ?: session?.accountTypeLabel ?: "Hauptaccount"
            val token = getAuthHeader()

            coroutineScope {
                val primaryDeferred = async { fetchMessagesWithToken(token, primaryLabel).getOrDefault(emptyList()) }
                val secondDeferred = async {
                    sessionManager.secondAccount?.let { acc ->
                        val server = session?.server ?: return@let emptyList<Message>()
                        fetchMessagesForSecondAccount(server, session.schoolname,
                            acc.username, acc.password, acc.label)
                    } ?: emptyList<Message>()
                }

                val primary = primaryDeferred.await()
                val second  = secondDeferred.await()

                val seenIds  = mutableSetOf<Int>()
                val seenKeys = mutableSetOf<String>()
                val merged   = (primary + second)
                    .sortedByDescending { it.sentDateTime }
                    .filter { msg ->
                        seenIds.add(msg.id) &&
                        seenKeys.add("${msg.subject}|${msg.sender?.userId}|${msg.sentDateTime}")
                    }
                Result.success(merged)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getMessageWithAttachments(msg: Message): Result<Message> = withSessionRetry {
        try {
            val token = tokenForMessage(msg) ?: return@withSessionRetry Result.success(msg)
            val resp  = service().getMessageDetail(msg.id, "Bearer $token")
            val raw   = rawBody(resp) ?: return@withSessionRetry Result.success(msg)
            val obj   = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val atts  = obj.getAsJsonArray("storageAttachments")
                ?.mapNotNull { el ->
                    val o = el.asJsonObject
                    Attachment(
                        id   = o.get("id")?.takeIf   { !it.isJsonNull }?.asString,
                        name = o.get("name")?.takeIf { !it.isJsonNull }?.asString
                    )
                } ?: emptyList()
            val history = obj.getAsJsonArray("replyHistory")
                ?.mapNotNull { el ->
                    try {
                        val o = el.asJsonObject
                        val sender = o.getAsJsonObject("sender")?.let { s ->
                            MessageSender(
                                displayName = s.get("displayName")?.takeIf { !it.isJsonNull }?.asString,
                                userId      = s.get("userId")?.takeIf { !it.isJsonNull }?.asInt,
                                imageUrl    = s.get("imageUrl")?.takeIf { !it.isJsonNull }?.asString
                            )
                        }
                        val histAtts = o.getAsJsonArray("storageAttachments")
                            ?.mapNotNull { ae ->
                                val ao = ae.asJsonObject
                                Attachment(
                                    id   = ao.get("id")?.takeIf   { !it.isJsonNull }?.asString,
                                    name = ao.get("name")?.takeIf { !it.isJsonNull }?.asString
                                )
                            }
                        ReplyMessage(
                            id           = o.get("id")?.takeIf { !it.isJsonNull }?.asInt,
                            subject      = o.get("subject")?.takeIf { !it.isJsonNull }?.asString,
                            content      = o.get("content")?.takeIf { !it.isJsonNull }?.asString,
                            sender       = sender,
                            sentDateTime = o.get("sentDateTime")?.asString?.takeIf { it.isNotBlank() } ?: "",
                            storageAttachments = histAtts
                        )
                    } catch (_: Exception) { null }
                }?.takeIf { it.isNotEmpty() }
            Result.success(msg.copy(attachmentList = atts, replyHistory = history))
        } catch (e: Exception) { Result.success(msg) }
    }

    suspend fun downloadAttachment(attachmentId: String, msg: Message): Result<ByteArray> = withSessionRetry {
        try {
            val token = tokenForMessage(msg) ?: return@withSessionRetry Result.failure(Exception("Nicht angemeldet"))
            val urlResp = service().getAttachmentStorageUrl(attachmentId, "Bearer $token")
            val urlRaw  = rawBody(urlResp) ?: return@withSessionRetry Result.failure(Exception("Keine Download-URL"))
            val storageUrl: AttachmentStorageUrl = parseJson(urlRaw)
            val downloadUrl = storageUrl.downloadUrl ?: return@withSessionRetry Result.failure(Exception("Keine URL in Antwort"))
            val headers   = storageUrl.additionalHeaders?.associate { it.key!! to it.value!! } ?: emptyMap()
            val algorithm = headers["x-amz-server-side-encryption-customer-algorithm"] ?: ""
            val encKey    = headers["x-amz-server-side-encryption-customer-key"] ?: ""
            val encKeyMd5 = headers["x-amz-server-side-encryption-customer-key-md5"] ?: ""
            val dlResp = service().downloadFromStorage(downloadUrl, algorithm, encKey, encKeyMd5)
            val bytes  = dlResp.body()?.bytes() ?: return@withSessionRetry Result.failure(Exception("Leere Antwort vom Speicher"))
            Result.success(bytes)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getUnreadMessageCount(): Int = withSessionRetry {
        try {
            val token = getAuthHeader()
            val resp = service().getMessagesStatus(token)
            if (!resp.isSuccessful) return@withSessionRetry Result.success(0)
            val raw = resp.body()?.string()?.trim() ?: return@withSessionRetry Result.success(0)
            Result.success(parseJson<MessagesStatusResponse>(raw).unreadMessagesCount)
        } catch (_: Exception) { Result.success(0) }
    }.getOrDefault(0)

    suspend fun downloadHomeworkAttachment(homeworkId: Int, attachment: HomeworkAttachment): Result<Pair<ByteArray, String>> {
        return try {
            val token = getAuthHeader()
            val attId = attachment.id ?: return Result.failure(Exception("Keine Anhang-ID"))
            val resp = service().downloadHomeworkAttachment(token, homeworkId, attId)
            if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code()}"))
            val bytes = resp.body()?.bytes() ?: return Result.failure(Exception("Leere Antwort"))
            val disposition = resp.headers()["Content-Disposition"] ?: ""
            val filename = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';
]+)""", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.get(1)?.trim()
                ?: attachment.name ?: attachment.uploadedFileName ?: "anhang"
            Result.success(Pair(bytes, filename))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Absences ──────────────────────────────────────────────────────────────

    suspend fun getAbsences(forceRefresh: Boolean = false, excuseStatusId: Int = -1): Result<List<Absence>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheAbsences },
        store = { cacheAbsences = it },
    ) {
        try {
            val token = getAuthHeader()
            val session = sessionManager.session
            if (session?.personType == 12 && sessionManager.studentId == 0) {
                getHomework(forceRefresh = false)
            }
            val studentId = sessionManager.studentId
            if (studentId == 0) return@withCacheOrFetch Result.success(emptyList<Absence>())
            val today = LocalDate.now()
            val yearStart = if (today.monthValue >= 8) today.year else today.year - 1
            val startDate = "${yearStart}0801"
            val endDate   = "${yearStart + 1}1231" // End of next calendar year to be safe
            val resp = service().getAbsences(token, startDate, endDate, studentId, excuseStatusId)
            val raw = rawBody(resp) ?: return@withCacheOrFetch Result.success(emptyList<Absence>())
            val absResp: AbsencesResponse = parseJson(raw)
            Result.success(
                (absResp.data?.absences ?: emptyList())
                    .sortedByDescending { it.startDate ?: 0 }
            )
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAbsencesMeta(forceRefresh: Boolean = false): Result<AbsencesMetaData> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheAbsencesMeta },
        store = { cacheAbsencesMeta = it },
    ) {
        try {
            val token = getAuthHeader()
            val resp = service().getAbsencesMeta(token)
            val raw = rawBody(resp) ?: return@withCacheOrFetch Result.failure(Exception("Keine Daten"))
            val metaResp: AbsencesMetaResponse = parseJson(raw)
            metaResp.data?.let { Result.success(it) } ?: Result.failure(Exception("Ungültige Daten"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createAbsence(req: CreateAbsenceRequest): Result<Absence> = withSessionRetry {
        try {
            val token = getAuthHeader()
            val resp = service().createAbsence(token, req)
            val raw = rawBody(resp) ?: return@withSessionRetry Result.failure(Exception("Fehler beim Erstellen"))
            val json = JsonParser.parseString(raw).asJsonObject
            val resultObj = json.getAsJsonObject("data")?.getAsJsonObject("result")
            if (resultObj != null) {
                Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            } else {
                Result.failure(Exception("Fehler: " + (json.getAsJsonObject("data")?.getAsJsonArray("conflicts")?.toString() ?: "Unbekannter Fehler")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateAbsence(id: Int, req: CreateAbsenceRequest): Result<Absence> = withSessionRetry {
        try {
            val token = getAuthHeader()
            val resp = service().updateAbsence(token, id, req)
            val raw = rawBody(resp) ?: return@withSessionRetry Result.failure(Exception("Fehler beim Aktualisieren"))
            val json = JsonParser.parseString(raw).asJsonObject
            val resultObj = json.getAsJsonObject("data")?.getAsJsonObject("result")
            if (resultObj != null) {
                Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            } else {
                Result.failure(Exception("Fehler beim Aktualisieren"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteAbsence(id: Int): Result<Unit> = withSessionRetry {
        try {
            val token = getAuthHeader()
            val resp = service().deleteAbsence(token, id)
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Löschen fehlgeschlagen"))
        } catch (e: Exception) { Result.failure(e) }
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
