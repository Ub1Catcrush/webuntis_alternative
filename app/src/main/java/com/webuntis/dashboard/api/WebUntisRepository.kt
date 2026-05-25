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

    /**
     * Performs a full re-authentication. 
     * @param force If true, skips the session age check (essential for recovery flows).
     */
    private suspend fun reAuthSilently(
        server: String, schoolname: String, username: String, password: String, force: Boolean = false
    ): Result<SessionData> = loginMutex.withLock {
        // Double check: if someone else refreshed while we waited for the lock
        if (sessionManager.isSessionFresh() && !force) {
            android.util.Log.d("WebUntis", "Using existing fresh session.")
            return sessionManager.session?.let { Result.success(it) } 
                ?: Result.failure(Exception("Sitzung verloren"))
        }

        android.util.Log.i("WebUntis", "Performing silent re-authentication (force=$force)...")
        bearerToken = null // Reset token to ensure it gets refreshed
        
        val rpc = loginViaJsonRpc(server, schoolname, username, password)
        val result = if (rpc.isSuccess) rpc else {
            kotlinx.coroutines.yield()
            loginViaRest(server, schoolname, username, password)
        }
        
        if (result.isSuccess) {
            val session = result.getOrNull()
            sessionManager.storedCredentials = Pair(username, password)
            
            // Auto-recover lost studentId (important for parent accounts)
            if (session != null && sessionManager.studentId == 0) {
                val id = if (session.classId > 0) session.classId else session.personId
                if (id > 0) sessionManager.studentId = id
            }
            
            fetchBearerToken()
            clearAllDataCaches() // Flush stale data from old session
            android.util.Log.i("WebUntis", "Silent re-auth successful. Session valid.")
        } else {
            android.util.Log.e("WebUntis", "Silent re-auth FAILED: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    private suspend fun reLoginIfNeeded() {
        if (sessionManager.isSessionFresh()) return
        val creds   = sessionManager.storedCredentials ?: return
        val session = sessionManager.session ?: return
        reAuthSilently(session.server, session.schoolname, creds.first, creds.second, force = false)
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
        if (result.isSuccess) {
            store(CacheEntry(System.currentTimeMillis(), result))
        }
        return result
    }

    private suspend fun <T> withSessionRetry(block: suspend () -> Result<T>): Result<T> {
        return try {
            reLoginIfNeeded()
            val result = block()
            
            val error = result.exceptionOrNull()
            if (error is SessionExpiredException || 
                (error != null && (error.message?.contains("-32001") == true || error.message?.contains("Session abgelaufen") == true))) {
                throw SessionExpiredException(error.message ?: "Session abgelaufen")
            }
            
            if (result.isSuccess) sessionManager.touchSession()
            result
        } catch (e: SessionExpiredException) {
            android.util.Log.w("WebUntis", "Session expired mid-request — starting auto-recovery")
            val creds   = sessionManager.storedCredentials
            val session = sessionManager.session
            if (creds == null || session == null) return Result.failure(e)
            
            val reAuth = reAuthSilently(session.server, session.schoolname, creds.first, creds.second, force = true)
            if (reAuth.isFailure) return Result.failure(reAuth.exceptionOrNull() ?: e)
            
            try {
                val retry = block()
                if (retry.isSuccess) sessionManager.touchSession()
                retry
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class CacheEntry<T>(val fetchedAt: Long, val data: Result<T>)

    private var cacheTimetable:    CacheEntry<List<TimetableDay>>?                        = null
    private var cacheHomework:     CacheEntry<Pair<List<Homework>, Map<String, String>>>? = null
    private var cacheEvents:       CacheEntry<List<SchoolEvent>>?                         = null
    private var cacheClassbook:    CacheEntry<List<ClassbookEntry>>?                      = null
    private var cacheAbsences:     CacheEntry<List<Absence>>?                             = null
    private var cacheMessages:     CacheEntry<List<Message>>?                             = null
    private var cacheAbsencesMeta: CacheEntry<AbsencesMetaData>?                        = null

    fun clearAllCaches() {
        clearAllDataCaches()
        sessionManager.clearAll()
    }

    private fun clearAllDataCaches() {
        cacheTimetable = null; cacheHomework = null; cacheEvents = null
        cacheClassbook = null; cacheAbsences = null; cacheMessages = null
        cacheAbsencesMeta = null
    }

    fun isHomeworkCacheFresh():  Boolean { val e = cacheHomework  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isEventsCacheFresh():    Boolean { val e = cacheEvents    ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isClassbookCacheFresh(): Boolean { val e = cacheClassbook ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isAbsencesCacheFresh():  Boolean { val e = cacheAbsences  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isMessagesCacheFresh():  Boolean { val e = cacheMessages  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isTimetableCacheFresh(): Boolean { val e = cacheTimetable ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }

    private val gson: Gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    private fun service(): WebUntisService {
        val host = sessionManager.session?.server ?: error("Kein Server konfiguriert")
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
        if (r.code() == 401) throw SessionExpiredException()

        val raw = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
        val bodyText = raw?.trim() ?: ""

        if (bodyText.contains("-32001") || 
            bodyText.contains("Session abgelaufen", ignoreCase = true) ||
            bodyText.contains("login.do", ignoreCase = true) ||
            bodyText.contains("index.do", ignoreCase = true) ||
            bodyText.contains("\"name\":\"anonym\"", ignoreCase = true)) {
            throw SessionExpiredException()
        }

        if (!r.isSuccessful) {
            val msg = tryExtractMessage(bodyText) ?: "HTTP ${r.code()}"
            throw Exception(msg)
        }
        
        if (bodyText.startsWith("<html", ignoreCase = true) || bodyText.startsWith("<!DOCTYPE", ignoreCase = true)) {
            throw SessionExpiredException()
        }
        
        return bodyText.ifEmpty { null }
    }

    private fun tryExtractMessage(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val je = JsonParser.parseString(raw).asJsonObject
            je["message"]?.asString ?: je["error"]?.asJsonObject?.get("message")?.asString
        } catch (e: Exception) {
            raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(150).ifEmpty { null }
        }
    }

    private suspend fun fetchBearerToken(): Result<String?> {
        return try {
            val resp = service().getBearerToken()
            val raw = rawBody(resp) ?: return Result.success(null)
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
        val result = if (rpc.isSuccess) rpc else {
            kotlinx.coroutines.yield()
            loginViaRest(server, schoolname, username, password)
        }
        if (result.isSuccess) {
            val session = result.getOrNull()
            if (session != null && sessionManager.studentId == 0) {
                val id = if (session.classId > 0) session.classId else session.personId
                if (id > 0) sessionManager.studentId = id
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
                ?: return Result.failure(Exception("Leere Antwort vom Server"))
            val rpcResp: JsonRpcResponse<AuthResult> =
                parseJson(raw, object : TypeToken<JsonRpcResponse<AuthResult>>() {})
            if (rpcResp.error != null) return Result.failure(Exception(rpcResp.error.message))
            val res = rpcResp.result ?: return Result.failure(Exception("Keine Daten in Antwort"))
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
                ?: return Result.failure(Exception("Leere Antwort vom Server"))
            val loginResp: LoginResponse = parseJson(raw)
            val data = loginResp.data ?: return Result.failure(Exception("Ungültige Antwort"))
            val sessionId = data.sessionId ?: return Result.failure(Exception("Keine Session-ID erhalten"))
            val session = SessionData(
                server = server, schoolname = schoolname, username = username,
                sessionId = sessionId, personId = data.person?.id ?: 0,
                classId = data.schoolyearData?.klasse?.id ?: 0,
                personName = data.person?.name ?: username,
                personType = data.person?.type ?: 0
            )
            sessionManager.session = session
            Result.success(session)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun logout() {
        try { service().logout() } catch (_: Exception) {}
        bearerToken = null
        clearAllDataCaches()
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
                val raw = rawBody(response) ?: return Result.success(emptyList())
                val ttResp: TimetableV1Response = parseJson(raw)
                val lessons = ttResp.toLessons()
                val enriched = enrichLessonsWithDetail(lessons, effectiveClassId, 5)
                Result.success(enriched)
            } catch (e: Exception) {
                Result.failure(e)
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
            Result.failure(e)
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

    private fun mergeOverlappingLessons(lessons: List<Lesson>): List<Lesson> {
        if (lessons.size < 2) return lessons
        val result = mutableListOf<Lesson>()
        val cancelledPool = lessons.filter { it.isCancelled }.toMutableList()
        val active = lessons.filter { !it.isCancelled }

        active.forEach { a ->
            val overlapping = cancelledPool.filter { c ->
                maxOf(a.startTime, c.startTime) < minOf(a.endTime, c.endTime)
            }
            if (overlapping.isNotEmpty()) {
                val insteadOf = overlapping.map { it.subjectName }.distinct().joinToString(", ")
                val replacedTeachers = overlapping.flatMap { c ->
                    c.te?.mapNotNull { it.longname ?: it.name } ?: emptyList<String>()
                }.distinct()
                val combinedRemoved = ((a.removedTeachers ?: emptyList<String>()) + replacedTeachers).distinct()
                val newLstype = if (a.lstype == null || a.lstype == "ls") "subst" else a.lstype
                result.add(a.copy(
                    replacedSubject = insteadOf, 
                    lstype = newLstype,
                    removedTeachers = combinedRemoved.ifEmpty { null }
                ))
                val overlappingIds = overlapping.map { it.id }.toSet()
                cancelledPool.removeAll { it.id in overlappingIds }
            } else {
                result.add(a)
            }
        }
        result.addAll(cancelledPool)
        return result.sortedBy { it.startTime }
    }

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

    suspend fun getHomework(forceRefresh: Boolean = false): Result<Pair<List<Homework>, Map<String, String>>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheHomework },
        store = { cacheHomework = it },
    ) {
        try {
            val token = getAuthHeader()
            val start = LocalDate.now().minusDays(30).toUntis()
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
            val raw = rawBody(resp) ?: return@withCacheOrFetch Result.success(emptyList<SchoolEvent>())
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

    suspend fun getUnreadMessageCount(): Int = withSessionRetry {
        try {
            val token = getAuthHeader()
            val resp = service().getMessagesStatus(token)
            val raw = rawBody(resp) ?: return@withSessionRetry Result.success(0)
            Result.success(parseJson<MessagesStatusResponse>(raw).unreadMessagesCount)
        } catch (e: Exception) {
            if (e is SessionExpiredException) throw e
            Result.success(0)
        }
    }.getOrDefault(0)

    suspend fun getAbsences(forceRefresh: Boolean = false, excuseStatusId: Int = -1): Result<List<Absence>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheAbsences },
        store = { cacheAbsences = it },
    ) {
        try {
            val token = getAuthHeader()
            val studentId = sessionManager.studentId
            if (studentId == 0) return@withCacheOrFetch Result.success(emptyList())
            val today = LocalDate.now()
            val yearStart = if (today.monthValue >= 8) today.year else today.year - 1
            val startDate = "${yearStart}0801"
            val endDate   = "${yearStart + 1}1231"
            val resp = service().getAbsences(token, startDate, endDate, studentId, excuseStatusId)
            val raw = rawBody(resp) ?: return@withCacheOrFetch Result.success(emptyList())
            val absResp: AbsencesResponse = parseJson(raw)
            Result.success((absResp.data?.absences ?: emptyList()).sortedByDescending { it.startDate ?: 0 })
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
            if (resultObj != null) Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            else Result.failure(Exception("Fehler: " + (json.getAsJsonObject("data")?.getAsJsonArray("conflicts")?.toString() ?: "Unbekannter Fehler")))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateAbsence(id: Int, req: CreateAbsenceRequest): Result<Absence> = withSessionRetry {
        try {
            val token = getAuthHeader()
            val resp = service().updateAbsence(token, id, req)
            val raw = rawBody(resp) ?: return@withSessionRetry Result.failure(Exception("Fehler beim Aktualisieren"))
            val json = JsonParser.parseString(raw).asJsonObject
            val resultObj = json.getAsJsonObject("data")?.getAsJsonObject("result")
            if (resultObj != null) Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            else Result.failure(Exception("Fehler beim Aktualisieren"))
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
    val label: String get() = when { isToday -> "Heute"; isTomorrow -> "Morgen"; else -> date.format(DateTimeFormatter.ofPattern("EEE dd.MM.", java.util.Locale.GERMAN)) }
}
