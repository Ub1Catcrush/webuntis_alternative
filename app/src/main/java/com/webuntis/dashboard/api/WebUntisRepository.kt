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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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

    /**
     * Resolves the logged-in person's own class element id via /timetable/filter's "preSelected".
     * Many WebUntis servers don't include a usable class id in the authenticate/login response
     * itself, so this dedicated call is the reliable source. Must be awaited during login/re-auth
     * — BEFORE the UI is allowed to render — so the class-timetable toggle is correct on the very
     * first frame instead of only appearing after a later reload happens to discover it.
     */
    private suspend fun resolveClassId(): Int? {
        return try {
            val token = getAuthHeader() ?: return null
            val today = LocalDate.now().toIso()
            val resp = service().getTimetableFilterV1(
                authorization = token,
                start = today, end = today
            )
            val raw = rawBody(resp) ?: return null
            val filterResp: TimetableFilterResponse = parseJson(raw)
            filterResp.preSelected?.id?.takeIf { it > 0 }
        } catch (e: Exception) {
            android.util.Log.w("WebUntis", "Could not resolve classId via /timetable/filter: ${e.message}")
            null
        }
    }

    /** Ensures [sessionManager]'s persisted session has a class id, resolving it if missing. */
    private suspend fun ensureClassIdResolved(session: SessionData): SessionData {
        if (session.classId > 0) return session
        val resolved = resolveClassId() ?: return session
        val updated = session.copy(classId = resolved)
        sessionManager.session = updated
        android.util.Log.i("WebUntis", "Resolved classId=$resolved via /timetable/filter")
        return updated
    }

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
            fetchBearerToken()

            // Resolve classId now — awaited — so the class-timetable toggle is already correct
            // by the time the UI renders, instead of only appearing after a later reload.
            val resolvedSession = session?.let { ensureClassIdResolved(it) } ?: session

            // Auto-recover lost studentId for non-parent accounts only: for a student's own
            // login, personId already IS their own timetable/absences/classbook element id.
            // NEVER fall back to classId here — it's the class's element id (e.g. "8c"'s id),
            // not the student's, and silently locking studentId to it broke personal/combined
            // timetable, absences, classbook and events (HTTP 500/404), while class mode kept
            // working since it's the only feature that doesn't depend on studentId. Parent
            // accounts (personType=12) must go through primeCachedElementIdIfNeeded()'s proper
            // appData/homework resolution instead — their own personId is the guardian's, not
            // the child's.
            if (resolvedSession != null && sessionManager.studentId == 0 && resolvedSession.personType != 12) {
                val id = resolvedSession.personId
                if (id > 0) sessionManager.studentId = id
            }

            clearAllDataCaches() // Flush stale data from old session
            android.util.Log.i("WebUntis", "Silent re-auth successful. Session valid.")
            return resolvedSession?.let { Result.success(it) } ?: result
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
    private var cacheSchoolYear:   CacheEntry<List<com.webuntis.dashboard.model.SchoolYearInfo>>? = null
    private var cacheAbsences:     CacheEntry<List<Absence>>?                             = null
    private var cacheMessages:     CacheEntry<List<Message>>?                             = null
    private var cacheSentMessages: CacheEntry<List<Message>>?                             = null
    private var cacheDraftMessages:CacheEntry<List<Message>>?                             = null
    private var cacheTeachers:     List<com.webuntis.dashboard.model.RecipientPerson>?   = null
    private var cacheAbsencesMeta: CacheEntry<AbsencesMetaData>?                        = null
    private var cacheTimegrid:     CacheEntry<List<com.webuntis.dashboard.model.TimegridRow>>? = null

    /**
     * Full reset: clears data caches AND wipes the session/credentials/settings entirely.
     * DANGER: this logs the user out. Only use for an actual "delete everything" action —
     * never for reacting to a harmless display setting change (use [clearDataCachesOnly] there).
     */
    fun resetEverythingIncludingSession() {
        clearAllDataCaches()
        sessionManager.clearAll()
    }

    /** Invalidates only data caches (timetable, absences, …) without touching the session or credentials. */
    fun clearDataCachesOnly() {
        clearAllDataCaches()
    }

    private fun clearAllDataCaches() {
        cacheTimetable = null; cacheHomework = null; cacheEvents = null
        cacheClassbook = null; cacheSchoolYear = null; cacheAbsences = null; cacheMessages = null; cacheSentMessages = null; cacheDraftMessages = null; cacheTeachers = null
        cacheAbsencesMeta = null; cacheTimegrid = null
    }

    /** Switches between the personal ("MY_TIMETABLE") and class ("STANDARD") timetable views. */
    fun setTimetableViewMode(mode: SessionManager.TimetableViewMode) {
        if (sessionManager.timetableViewMode == mode) return
        sessionManager.timetableViewMode = mode
        cacheTimetable = null
    }

    /** Updates which class-plan subjects are allowed to fill gaps in COMBINED mode and refreshes. */
    fun setCombinedOverlaySubjects(subjects: Set<String>) {
        if (sessionManager.combinedOverlaySubjects == subjects) return
        sessionManager.combinedOverlaySubjects = subjects
        cacheTimetable = null
    }

    /**
     * Returns the distinct subject names currently available in the class plan, for the
     * "which subjects should fill gaps in my plan?" picker. Best-effort — returns an empty
     * list if the class plan can't be fetched right now.
     */
    suspend fun getAvailableClassSubjects(): List<ClassSubjectOption> {
        val session = sessionManager.session ?: return emptyList()
        if (!sessionManager.canShowClassTimetable) return emptyList()
        val today = LocalDate.now()
        val startIso = today.toIso()
        val endIso = today.plusDays(sessionManager.timetableDays.coerceAtLeast(7).toLong()).toIso()
        val result = fetchLessonsV1(startIso, endIso, session.classId, "CLASS", "STANDARD", 1)
        val lessons = result.getOrNull() ?: return emptyList()
        // Keyed by shortName (what's actually matched/stored) — first non-blank long name wins,
        // since the same abbreviation can occasionally carry slightly different lessonInfo per entry.
        val byShortName = linkedMapOf<String, String>()
        lessons.forEach { lesson ->
            val short = lesson.subjectName.takeIf { it.isNotBlank() && it != "–" } ?: return@forEach
            val long = lesson.subjectLongName.takeIf { it.isNotBlank() && it != "–" } ?: short
            if (byShortName[short].isNullOrBlank() || byShortName[short] == short) byShortName[short] = long
        }
        return byShortName.map { (short, long) -> ClassSubjectOption(short, long) }
            .sortedBy { it.displayLabel.lowercase() }
    }

    fun isHomeworkCacheFresh():  Boolean { val e = cacheHomework  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }

    /**
     * Builds a short↔long lookup for subjects and teachers from whatever timetable data is
     * currently cached (falls back to a small fresh fetch if nothing is cached yet). Homework,
     * events and messages each carry only a short code or only a long name from their own
     * endpoints — this lets the UI show both, e.g. "Mathematik (M)" / "Müller (Mü)".
     */
    suspend fun getNameCatalog(): NameCatalog {
        val cachedLessons = cacheTimetable?.data?.getOrNull()?.flatMap { it.lessons }
        val lessons = if (!cachedLessons.isNullOrEmpty()) cachedLessons else {
            val start = LocalDate.now().minusDays(7).toUntis()
            val end   = LocalDate.now().plusDays(14).toUntis()
            fetchLessonsInRange(start, end).getOrNull() ?: emptyList()
        }
        val subjectMap = linkedMapOf<String, String>()
        val teacherMap = linkedMapOf<String, String>()
        lessons.forEach { l ->
            val subjShort = l.subjectName.takeIf { it.isNotBlank() && it != "–" }
            val subjLong  = l.subjectLongName.takeIf { it.isNotBlank() && it != "–" }
            if (subjShort != null && subjLong != null && subjectMap[subjShort].isNullOrBlank()) {
                subjectMap[subjShort] = subjLong
            }
            l.te?.forEach { t ->
                val short = t.name?.takeIf { it.isNotBlank() }
                val long  = t.longname?.takeIf { it.isNotBlank() }
                if (short != null && long != null && teacherMap[short].isNullOrBlank()) {
                    teacherMap[short] = long
                }
            }
        }
        return NameCatalog(subjectMap, teacherMap)
    }
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

        // WebUntis returns 403 (not 401) when a JWT Bearer token has expired.
        // Only treat 403 as session-expired when the body is an HTML error page
        // (i.e. not a real API permission error which would return JSON).
        if (r.code() == 403 &&
            (bodyText.startsWith("<html", ignoreCase = true) ||
             bodyText.startsWith("<!DOCTYPE", ignoreCase = true) ||
             bodyText.isBlank())) {
            throw SessionExpiredException()
        }

        if (bodyText.contains("-32001") ||
            bodyText.contains("Session abgelaufen", ignoreCase = true) ||
            bodyText.contains("login.do", ignoreCase = true) ||
            bodyText.contains("index.do", ignoreCase = true) ||
            bodyText.contains("\"name\":\"anonym\"", ignoreCase = true)) {
            throw SessionExpiredException()
        }

        if (!r.isSuccessful) {
            val extracted = tryExtractMessage(bodyText)
            val msg = if (extracted != null) "$extracted (HTTP ${r.code()})" else "HTTP ${r.code()}"
            android.util.Log.w("WebUntis", "rawBody: request failed — HTTP ${r.code()} url=${r.raw().request.url} body=${bodyText.take(300)}")
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

    /** Extracts the tenant_id claim from the current JWT bearer token (base64url decode of payload). */
    private fun tenantIdFromToken(): String? {
        val token = bearerToken ?: return null
        return try {
            val payload = token.split(".").getOrNull(1) ?: return null
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE))
            com.google.gson.JsonParser.parseString(json).asJsonObject.get("tenant_id")?.asString
        } catch (e: Exception) { null }
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
        startIso: String, endIso: String, elementId: Int,
        resourceType: String = "STUDENT", timetableType: String = "MY_TIMETABLE"
    ): retrofit2.Response<okhttp3.ResponseBody> {
        val token = getAuthHeader()
        val resp = if (token != null) {
            service().getTimetableV1Auth(
                authorization = token,
                start = startIso, end = endIso,
                resourceType = resourceType, resources = elementId.toString(),
                timetableType = timetableType
            )
        } else {
            service().getTimetableV1(
                start = startIso, end = endIso,
                resourceType = resourceType, resources = elementId.toString(),
                timetableType = timetableType
            )
        }
        if (resp.code() in listOf(401, 403)) {
            bearerToken = null
            val fresh = getAuthHeader() ?: return resp
            return service().getTimetableV1Auth(
                authorization = fresh,
                start = startIso, end = endIso,
                resourceType = resourceType, resources = elementId.toString(),
                timetableType = timetableType
            )
        }
        return resp
    }

    suspend fun login(
        server: String, schoolname: String, username: String, password: String
    ): Result<SessionData> = loginMutex.withLock {
        // Defensively drop any stale in-memory bearer token from a previous session before
        // authenticating — this repository instance is a Singleton, so a token left over from
        // an earlier (now invalid) session could otherwise get reused and break every v1 call
        // (empty timetable, 500s, 404s) even though the login itself succeeds.
        bearerToken = null
        val rpc = loginViaJsonRpc(server, schoolname, username, password)
        val result = if (rpc.isSuccess) rpc else {
            kotlinx.coroutines.yield()
            loginViaRest(server, schoolname, username, password)
        }
        if (result.isSuccess) {
            // Persist credentials so auto-login works after app restart
            sessionManager.storedCredentials = Pair(username, password)
            val tokenResult = fetchBearerToken()
            if (tokenResult.isFailure) {
                android.util.Log.w("WebUntis", "Bearer token fetch failed right after login: ${tokenResult.exceptionOrNull()?.message}")
            }
            val session = result.getOrNull()
            // Resolve classId now — awaited — so the class-timetable toggle is already correct
            // by the time the UI renders (e.g. right after LoginViewModel sets isLoggedIn=true),
            // instead of only appearing later once some other request happens to discover it.
            val resolvedSession = session?.let { ensureClassIdResolved(it) } ?: session
            // See reAuthSilently() for why this must never fall back to classId, and must be
            // restricted to non-parent accounts.
            if (resolvedSession != null && sessionManager.studentId == 0 && resolvedSession.personType != 12) {
                val id = resolvedSession.personId
                if (id > 0) sessionManager.studentId = id
            }
            return@withLock resolvedSession?.let { Result.success(it) } ?: result
        }
        result
    }

    suspend fun primeCachedElementIdIfNeeded() {
        val session = sessionManager.session ?: return

        // Self-heal installs affected by earlier bugs where studentId got silently set to a
        // *wrong* value instead of the student's own element id — either the class's own id
        // (e.g. "8c" → 688), or, for parents, an arbitrary classmate's id picked up from a
        // class-wide homework's elementIds list. Both corrupted values look just as "valid" as
        // a real studentId to the plain `studentId != 0` check below, so they'd otherwise be
        // stuck forever, breaking personal/combined timetable, absences, classbook and events
        // (HTTP 500/404) while class mode kept working (the only feature independent of
        // studentId). For parent accounts we can't reliably tell a wrong-but-plausible id apart
        // from a correct one after the fact, so instead of pattern-matching known-bad values we
        // force one authoritative re-resolution via /app/data per install (studentIdHealedV2),
        // regardless of whatever is currently cached.
        val knownCorruptValue = session.classId > 0 && sessionManager.studentId == session.classId
        val needsForcedHeal = session.personType == 12 && !sessionManager.studentIdHealedV2
        if (knownCorruptValue || needsForcedHeal) {
            android.util.Log.w("WebUntis", "Forcing studentId re-resolution (corrupt=$knownCorruptValue, unhealed=$needsForcedHeal)")
            sessionManager.studentId = 0
        }

        if (sessionManager.studentId != 0) return
        // For a student's own account, personId already IS their own element id — no network
        // round-trip needed. Only parent accounts (personType=12) need the appData/homework
        // resolution below, since their personId is the guardian's, not the child's.
        if (session.personType != 12) {
            if (session.personId > 0) sessionManager.studentId = session.personId
            return
        }
        // For parent accounts (personType=12) the timetable needs the child's element ID.
        // Try the /app/data endpoint first — it's authoritative and returns the currently
        // selected student's own element ID (unlike the homework-based fallback below, which
        // can only guess).
        try {
            val token = getAuthHeader()
            if (token != null) {
                val resp = service().getAppData(token)
                val raw  = rawBody(resp)
                if (raw != null) {
                    val json = com.google.gson.JsonParser.parseString(raw).asJsonObject
                    val userObj = json.getAsJsonObject("user")
                    val roles = userObj?.getAsJsonArray("roles")
                        ?.map { it.asString } ?: emptyList()

                    val elemId: Int? = when {
                        roles.contains("LEGAL_GUARDIAN") -> {
                            userObj?.getAsJsonArray("students")
                                ?.firstOrNull()
                                ?.asJsonObject
                                ?.get("id")?.asInt
                        }
                        roles.contains("STUDENT") -> {
                            userObj?.getAsJsonObject("person")
                                ?.get("id")?.asInt
                        }
                        else -> null
                    }

                    if (elemId != null && elemId > 0) {
                        sessionManager.studentId = elemId
                        sessionManager.studentIdHealedV2 = true
                        android.util.Log.i("WebUntis", "Resolved student elemId=$elemId from appData (roles=$roles)")
                        return
                    } else {
                        android.util.Log.w("WebUntis", "No elemId resolvable for roles=$roles, userId=${userObj?.get("id")?.asInt}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebUntis", "Could not resolve elemId from appData: ${e.message}")
        }
        // Last-resort fallback: try the homework endpoint, which also resolves the student
        // element (see getHomework() for why it only trusts individually-targeted records).
        getHomework(forceRefresh = true)
        if (sessionManager.studentId != 0) sessionManager.studentIdHealedV2 = true
    }

    private suspend fun loginViaJsonRpc(
        server: String, schoolname: String, username: String, password: String
    ): Result<SessionData> {
        return try {
            val body = JsonRpcRequest(
                method = "authenticate",
                params = mapOf<String, Any>("user" to username, "password" to password, "client" to "android")
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

        // Defensive re-resolution: TimetableViewModel.init{} can fire loadAll() before
        // LoginViewModel's own primeCachedElementIdIfNeeded() call has finished (e.g. the
        // Fragment is recreated while login/heal is still in flight), leaving studentId at 0
        // for parent accounts even though it would resolve correctly a moment later. Without
        // this, execution fell through all the way to the legacy JSON-RPC fallback below using
        // session.personId (the guardian's own id) as a timetable elementId — which the server
        // always rejects with "no such element" for parent accounts. Idempotent/cheap when
        // studentId is already resolved.
        if (session.personType == 12 && sessionManager.studentId == 0) {
            primeCachedElementIdIfNeeded()
        }

        val mode = sessionManager.timetableViewMode
        val classElementId = session.classId
        val effectiveClassId = sessionManager.studentId
        val canShowClass = sessionManager.canShowClassTimetable

        val startIso = "${startDate.substring(0,4)}-${startDate.substring(4,6)}-${startDate.substring(6,8)}"
        val endIso   = "${endDate.substring(0,4)}-${endDate.substring(4,6)}-${endDate.substring(6,8)}"

        // COMBINED: personal plan + selected class-plan subjects filled into the gaps.
        // Falls back to plain PERSONAL if no class id is known or nothing is selected to overlay.
        if (mode == SessionManager.TimetableViewMode.COMBINED && canShowClass && effectiveClassId != 0) {
            return try {
                coroutineScope {
                    val personalDeferred = async {
                        fetchLessonsV1(startIso, endIso, effectiveClassId, "STUDENT", "MY_TIMETABLE", 5)
                    }
                    val classDeferred = async {
                        fetchLessonsV1(startIso, endIso, classElementId, "CLASS", "STANDARD", 1)
                    }
                    val personalResult = personalDeferred.await()
                    val personal = personalResult.getOrNull()
                        ?: return@coroutineScope Result.failure(personalResult.exceptionOrNull() ?: Exception("Unbekannter Fehler"))
                    // The class-plan overlay is best-effort: if it fails, still show the personal plan.
                    val classLessons = classDeferred.await().getOrDefault(emptyList())
                    val allowed = sessionManager.combinedOverlaySubjects
                    Result.success(buildCombinedLessons(personal, classLessons, allowed))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        // In CLASS mode we show the whole class's timetable (resourceType=CLASS) instead of
        // the logged-in person's own schedule (resourceType=STUDENT). Falls back to PERSONAL
        // if no class element id is known (e.g. it wasn't resolved yet).
        val wantsClassView = mode == SessionManager.TimetableViewMode.CLASS && canShowClass

        if (wantsClassView || effectiveClassId != 0) {
            val elementId: Int
            val resourceType: String
            val timetableType: String
            val elementType: Int
            if (wantsClassView) {
                elementId = classElementId; resourceType = "CLASS"; timetableType = "STANDARD"; elementType = 1
            } else {
                elementId = effectiveClassId; resourceType = "STUDENT"; timetableType = "MY_TIMETABLE"; elementType = 5
            }
            return fetchLessonsV1(startIso, endIso, elementId, resourceType, timetableType, elementType)
        }

        // Parent accounts have no personal timetable of their own — session.personId is the
        // guardian's element id, never a valid timetable target (type 5 = student). Reaching
        // this point means studentId resolution failed (e.g. offline/appData error); firing the
        // request anyway would just produce a confusing "no such element" server error, so fail
        // fast with a clear message instead.
        if (session.personType == 12) {
            return Result.failure(Exception("Konnte Kind-ID nicht auflösen (bitte erneut versuchen)"))
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

    /** Fetches + enriches a single timetable/v1 view (personal or class). */
    private suspend fun fetchLessonsV1(
        startIso: String, endIso: String, elementId: Int,
        resourceType: String, timetableType: String, elementType: Int
    ): Result<List<Lesson>> {
        return try {
            val response = callTimetableV1(
                startIso, endIso, elementId,
                resourceType = resourceType, timetableType = timetableType
            )
            val raw = rawBody(response) ?: return Result.success(emptyList())
            val ttResp: TimetableV1Response = parseJson(raw)
            val lessons = ttResp.toLessons()
            val enriched = enrichLessonsWithDetail(lessons, elementId, elementType)
            Result.success(enriched)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Adds class-plan lessons into the personal list, but ONLY for slots that are actually empty
     * in the personal plan (same date, no time overlap with any personal lesson) and only for
     * subjects the user has opted into via [SessionManager.combinedOverlaySubjects]. Added entries
     * are marked [Lesson.isFromClassPlan] so the UI can label/style them distinctly.
     */
    private fun buildCombinedLessons(
        personal: List<Lesson>, classLessons: List<Lesson>, allowedSubjects: Set<String>
    ): List<Lesson> {
        if (allowedSubjects.isEmpty()) return personal
        val personalByDate = personal.groupBy { it.date }
        val overlay = classLessons.filter { cl ->
            if (cl.subjectName !in allowedSubjects && cl.subjectLongName !in allowedSubjects) return@filter false
            val sameDayPersonal = personalByDate[cl.date] ?: emptyList()
            sameDayPersonal.none { p -> p.startTime < cl.endTime && cl.startTime < p.endTime }
        }.map { it.copy(isFromClassPlan = true) }
        return personal + overlay
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
                val merged = mergeLessonsForCurrentView(lessons)
                TimetableDay(untisIntToDate(d), merged.sortedBy { it.startTime })
            }
        Result.success(byDate)
    }

    /**
     * Loads [numDays] school days starting from [anchorDate].
     * Only days that actually have lessons are counted (no empty days, no weekends).
     * Searches up to 60 calendar days in both directions to find real school days.
     */
    suspend fun getSchoolDaysFrom(
        anchorDate: LocalDate,
        numDays: Int,
        forceRefresh: Boolean = false
    ): Result<List<TimetableDay>> {
        // Fetch a wide enough window — ±60 days covers holidays and long breaks
        val windowStart = anchorDate.minusDays(if (anchorDate.isBefore(LocalDate.now())) 60 else 0)
        val windowEnd   = anchorDate.plusDays(60)
        val rangeResult = fetchLessonsInRange(windowStart.toUntis(), windowEnd.toUntis())
        if (rangeResult.isFailure) return Result.failure(rangeResult.exceptionOrNull()!!)

        val allSchoolDays = rangeResult.getOrThrow()
            .groupBy { it.date }.entries
            .filter { (d, lessons) ->
                val date = untisIntToDate(d)
                date.dayOfWeek.value <= 5 && lessons.isNotEmpty()
            }
            .sortedBy { it.key }
            .map { (d, lessons) ->
                TimetableDay(untisIntToDate(d), mergeLessonsForCurrentView(lessons).sortedBy { it.startTime })
            }

        // Find the index of the first day >= anchorDate
        val startIdx = allSchoolDays.indexOfFirst { !it.date.isBefore(anchorDate) }
            .takeIf { it >= 0 } ?: return Result.success(emptyList())

        return Result.success(allSchoolDays.drop(startIdx).take(numDays))
    }

    private fun untisIntToDate(d: Int): LocalDate {
        val s = d.toString().padStart(8, '0')
        return LocalDate.of(s.substring(0,4).toInt(), s.substring(4,6).toInt(), s.substring(6,8).toInt())
    }

    /**
     * Merges cancelled+active lesson pairs into a single "statt [Old Subject]" entry — but only
     * for the PERSONAL timetable, where an overlap reliably means "this replaces that". A CLASS
     * timetable can have several unrelated parallel courses (differentiated groups, religion vs.
     * ethics, electives) at the same time slot, so pairing by time overlap alone would risk
     * attaching the wrong "statt" label to an unrelated course. In CLASS mode, cancelled and
     * active lessons are therefore kept separate; [TimetableViewModel]'s grouping already displays
     * that mix vertically instead of merging it.
     */
    private fun mergeLessonsForCurrentView(lessons: List<Lesson>): List<Lesson> {
        if (sessionManager.timetableViewMode == SessionManager.TimetableViewMode.CLASS) {
            return lessons
        }
        // In COMBINED mode, only merge cancelled/active pairs among the personal-plan lessons —
        // overlay entries filled in from the class plan can include several unrelated parallel
        // class-plan subjects and must never be paired into a false "statt" substitution.
        val overlay = lessons.filter { it.isFromClassPlan }
        if (overlay.isEmpty()) return mergeOverlappingLessons(lessons)
        val personalOnly = lessons.filter { !it.isFromClassPlan }
        return mergeOverlappingLessons(personalOnly) + overlay
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

            if(detail.classes.size == 1)
            {
                if (detail.classes[0] != null &&
                    detail.classes[0] != 0 &&
                    sessionManager.classId == 0)
                    sessionManager.classId = detail.classes[0] as Int
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
            // Opportunistic studentId resolution — last resort only, see primeCachedElementIdIfNeeded()
            // for the primary (/app/data) resolution path. Two important constraints here, both
            // fixing real bugs that kept corrupting an already-correct studentId:
            //  1. Only ever act as a fallback (studentId still 0) — this used to run unconditionally
            //     on every single getHomework() call and could clobber a correctly resolved id later.
            //  2. Only trust records where elementIds has exactly one entry. For class-wide homework,
            //     elementIds lists *every* student in the class — blindly taking elementIds.firstOrNull()
            //     of the first record picked an essentially arbitrary classmate, not necessarily our own
            //     child, which still caused wrong-student HTTP 404s on absences/classbook/events even
            //     after the classId-based corruption was fixed.
            if (sessionManager.studentId == 0) {
                hwResp.data?.records
                    ?.firstOrNull { it.elementIds?.size == 1 }
                    ?.elementIds?.firstOrNull()
                    ?.let { if (it != 0) sessionManager.studentId = it }
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

    suspend fun downloadHomeworkAttachment(
        homeworkId: Int,
        attachment: HomeworkAttachment,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Pair<ByteArray, String>> = withSessionRetry {
        try {
            val token = getAuthHeader()
            val attachmentId = attachment.id ?: return@withSessionRetry Result.failure(Exception("Anhang-ID fehlt"))
            val resp = service().downloadHomeworkAttachment(token, homeworkId, attachmentId)

            if (resp.headers()["X-WebUntis-Session-Expired"] == "true" || resp.code() == 401) {
                throw SessionExpiredException()
            }
            
            if (!resp.isSuccessful) {
                return@withSessionRetry Result.failure(Exception("HTTP ${resp.code()}"))
            }

            val body = resp.body() ?: return@withSessionRetry Result.failure(Exception("Keine Daten"))
            val expectedLength = body.contentLength()
            val bytes = readBytesWithProgress(body, onProgress)
            if (bytes.isEmpty() || (expectedLength > 0 && bytes.size.toLong() < expectedLength)) {
                return@withSessionRetry Result.failure(
                    Exception("Download unvollständig (${bytes.size} von ${if (expectedLength > 0) expectedLength else "?"} Bytes)")
                )
            }
            val filename = attachment.uploadedFileName ?: attachment.name ?: "Anhang"
            Result.success(Pair(bytes, filename))
        } catch (e: Exception) {
            if (e is SessionExpiredException) throw e
            Result.failure(e)
        }
    }

    /** Returns the display name (e.g. "2025/2026") of the current school year, for UI use. */
    suspend fun getCurrentSchoolYearName(): Result<String> =
        getCurrentSchoolYear().mapCatching { it.name }

    private suspend fun getCurrentSchoolYear(): Result<com.webuntis.dashboard.model.SchoolYearInfo> =
        withCacheOrFetch(
            forceRefresh = false,
            cache = { cacheSchoolYear },
            store = { cacheSchoolYear = it },
        ) {
            try {
                val token = getAuthHeader()
                val resp = service().getSchoolYears(token)
                val raw = rawBody(resp) ?: return@withCacheOrFetch Result.failure(Exception("Keine Schuldaten"))
                val years: List<com.webuntis.dashboard.model.SchoolYearInfo> =
                    parseJson(raw, object : com.google.gson.reflect.TypeToken<List<com.webuntis.dashboard.model.SchoolYearInfo>>() {})
                Result.success(years)
            } catch (e: Exception) { Result.failure(e) }
        }.mapCatching { years ->
            val today = LocalDate.now()
            val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            years.firstOrNull { year ->
                val start = LocalDate.parse(year.dateRange.start, fmt)
                val end   = LocalDate.parse(year.dateRange.end, fmt)
                !today.isBefore(start) && !today.isAfter(end)
            } ?: years.firstOrNull() ?: throw Exception("Kein Schuljahr gefunden")
        }

    suspend fun getClassbookEntries(forceRefresh: Boolean = false): Result<List<ClassbookEntry>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheClassbook },
        store = { cacheClassbook = it },
    ) {
        try {
            val token = getAuthHeader()
            val today = LocalDate.now()
            val yearStart = if (today.monthValue >= 8) today.year else today.year - 1
            // Fetch current school year from API to get exact dates (avoids MULTIPLE_SCHOOLYEARS_IN_RANGE)
            val schoolYear = getCurrentSchoolYear().getOrNull()
            val start: String
            val end: String
            if (schoolYear != null) {
                // API returns "yyyy-MM-dd", service expects "yyyyMMdd"
                val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                val outFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                start = LocalDate.parse(schoolYear.dateRange.start, fmt).format(outFmt)
                end   = LocalDate.parse(schoolYear.dateRange.end, fmt).format(outFmt)
            } else {
                // Fallback: use a single month to avoid the multi-year error
                start = LocalDate.now().withDayOfMonth(1).toUntis()
                end   = LocalDate.now().toUntis()
            }
            val session = sessionManager.session
                ?: return@withCacheOrFetch Result.failure(Exception("Nicht angemeldet"))
            // See fetchLessonsInRange() for why the authoritative primeCachedElementIdIfNeeded()
            // (appData first, homework only as last resort) is used here instead of jumping
            // straight to the weaker homework-only fallback.
            if (session.personType == 12 && sessionManager.studentId == 0) {
                primeCachedElementIdIfNeeded()
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

    suspend fun getEvents(forceRefresh: Boolean = false, includePast: Boolean = false): Result<List<SchoolEvent>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { if (includePast) null else cacheEvents },
        store = { if (!includePast) cacheEvents = it },
    ) {
        try {
            val token = getAuthHeader()
            val eventSession = sessionManager.session
            if (eventSession?.personType == 12 && sessionManager.studentId == 0) {
                primeCachedElementIdIfNeeded()
            }
            val classId = sessionManager.studentId
            if (classId == 0) return@withCacheOrFetch Result.success(emptyList<SchoolEvent>())
            val today = LocalDate.now()
            // Use school year bounds to avoid MULTIPLE_SCHOOLYEARS_IN_RANGE error
            val schoolYear = getCurrentSchoolYear().getOrNull()
            val syFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val syStart = schoolYear?.let { LocalDate.parse(it.dateRange.start, syFmt) }
                ?: if (today.monthValue >= 8) today.withMonth(8).withDayOfMonth(1)
                   else today.withYear(today.year - 1).withMonth(8).withDayOfMonth(1)
            val syEnd = schoolYear?.let { LocalDate.parse(it.dateRange.end, syFmt) }
                ?: syStart.plusYears(1).withMonth(7).withDayOfMonth(31)
            val start = if (includePast) syStart else today
            val end   = minOf(today.plusDays(90), syEnd)
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
                            subjectLongName = subject?.longName,
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
            val sorted = if (includePast) events.sortedByDescending { it.date ?: 0 }
                         else events.sortedBy { it.date ?: 0 }
            Result.success(sorted)
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

    /**
     * Fetches the school's timegrid (lesson periods with start/end times).
     * Uses the current school year ID. Cached with normal TTL.
     */
    suspend fun getTimegrid(forceRefresh: Boolean = false): Result<List<com.webuntis.dashboard.model.TimegridRow>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheTimegrid },
        store = { cacheTimegrid = it },
    ) {
        try {
            val token = getAuthHeader()
            val schoolYear = getCurrentSchoolYear().getOrNull()
            val schoolYearId = schoolYear?.id ?: 0
            val resp = service().getTimegrid(token, schoolYearId)
            val raw = rawBody(resp) ?: return@withCacheOrFetch Result.success(emptyList())
            val timegridResp: com.webuntis.dashboard.model.TimegridResponse = parseJson(raw)
            Result.success(timegridResp.data?.rows ?: emptyList())
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAbsences(forceRefresh: Boolean = false, excuseStatusId: Int = -1): Result<List<Absence>> = withCacheOrFetch(
        forceRefresh = forceRefresh || excuseStatusId != -1,
        cache = { if (excuseStatusId != -1) null else cacheAbsences },
        store = { if (excuseStatusId == -1) cacheAbsences = it },
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
            // WebUntis write endpoints require Tenant-Id header (from JWT claim) + JSESSIONID cookie.
            // The Bearer token is intentionally omitted (scope mg:r = read-only).
            val tenantId = tenantIdFromToken()
            val resp = service().createAbsence(null, tenantId, req)
            val raw = rawBody(resp) ?: return@withSessionRetry Result.failure(Exception("Fehler beim Erstellen"))
            val json = JsonParser.parseString(raw).asJsonObject
            val resultObj = json.getAsJsonObject("data")?.getAsJsonObject("result")
            if (resultObj != null) Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            else Result.failure(Exception("Fehler: " + (json.getAsJsonObject("data")?.getAsJsonArray("conflicts")?.toString() ?: "Unbekannter Fehler")))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateAbsence(id: Int, req: CreateAbsenceRequest): Result<Absence> = withSessionRetry {
        try {
            val tenantId = tenantIdFromToken()
            val resp = service().updateAbsence(null, tenantId, id, req)
            val raw = rawBody(resp) ?: return@withSessionRetry Result.failure(Exception("Fehler beim Aktualisieren"))
            val json = JsonParser.parseString(raw).asJsonObject
            val resultObj = json.getAsJsonObject("data")?.getAsJsonObject("result")
            if (resultObj != null) Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            else Result.failure(Exception("Fehler beim Aktualisieren"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteAbsence(id: Int): Result<Unit> = withSessionRetry {
        try {
            val tenantId = tenantIdFromToken()
            val resp = service().deleteAbsence(null, tenantId, DeleteAbsenceRequest(listOf(id)))
            val raw = if (resp.isSuccessful) resp.body()?.string() else resp.errorBody()?.string()
            if (resp.isSuccessful) {
                // Response: {"data":{"success":true}} or just 2xx
                Result.success(Unit)
            } else {
                Result.failure(Exception("error_delete_failed"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    @Volatile private var secondBearerToken: String? = null

    private suspend fun fetchMessagesWithToken(token: String, label: String): List<Message> {
        val resp = service().getMessagesAuth(token)
        val effective = if (resp.code() in listOf(401, 403)) {
            // Token abgelaufen — neu holen und nochmal versuchen
            val fresh = getAuthHeader() ?: return emptyList()
            service().getMessagesAuth(fresh)
        } else resp
        val raw = rawBody(effective) ?: return emptyList()
        val msgsResp: MessagesResponse = parseJson(raw)
        return ((msgsResp.incomingMessages ?: emptyList()) +
                (msgsResp.readConfirmationMessages ?: emptyList()))
            .map { it.copy(accountLabel = label) }
    }

    private suspend fun fetchMessagesForSecondAccount(
        server: String, schoolname: String,
        username: String, password: String, label: String
    ): List<Message> {
        return try {
            val svc  = retrofitFactory.create(server)
            val body = JsonRpcRequest(
                method = "authenticate",
                params = mapOf<String, Any>("user" to username, "password" to password, "client" to "android")
            )
            val loginResp = svc.jsonRpcLogin(schoolname, body)
            val loginRaw  = loginResp.body()?.string()?.trim() ?: return emptyList()
            val rpcResp: JsonRpcResponse<AuthResult> =
                parseJson(loginRaw, object : TypeToken<JsonRpcResponse<AuthResult>>() {})
            val authResult = rpcResp.result ?: return emptyList()

            val resolvedLabel = label.ifBlank {
                authResult.personName?.takeIf { it.isNotBlank() } ?: username
            }
            // Metadaten des 2. Accounts aktualisieren (Name/Typ können sich ändern)
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
            val msgsResp: MessagesResponse = parseJson(raw)
            ((msgsResp.incomingMessages ?: emptyList()) +
                    (msgsResp.readConfirmationMessages ?: emptyList()))
                .map { it.copy(accountLabel = resolvedLabel) }
        } catch (e: Exception) {
            android.util.Log.e("WebUntis", "2. Account – Nachrichten konnten nicht geladen werden", e)
            emptyList()
        }
    }

    /** Gibt den Bearer-Token (inkl. "Bearer "-Prefix) zurück, der zu dieser Nachricht gehört. */
    private fun tokenForMessage(msg: Message): String? {
        val raw = bearerToken ?: return null
        val primaryHeader = "Bearer $raw"
        val secondLabel = sessionManager.secondAccount?.label ?: return primaryHeader
        return if (!msg.accountLabel.isNullOrBlank() && msg.accountLabel == secondLabel)
            secondBearerToken?.let { "Bearer $it" } ?: primaryHeader
        else primaryHeader
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

            val token   = getAuthHeader() ?: return@withCacheOrFetch Result.failure(Exception("Nicht authentifiziert"))
            val primary = fetchMessagesWithToken(token, primaryLabel)
            val second  = sessionManager.secondAccount?.let { acc ->
                val server = session?.server ?: return@let emptyList()
                fetchMessagesForSecondAccount(server, session.schoolname,
                    acc.username, acc.password, acc.label)
            } ?: emptyList()

            // Deduplizierung: IDs sind nur innerhalb eines Accounts eindeutig.
            // accountLabel + id als zusammengesetzter Key verhindert, dass Nachrichten
            // des 2. Accounts fälschlicherweise herausgefiltert werden.
            val seenKeys = mutableSetOf<String>()
            val merged   = (primary + second)
                .sortedByDescending { it.sentDateTimeForSorting }
                .filter { msg -> seenKeys.add("${msg.accountLabel}|${msg.id}") }
            Result.success(merged)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getMessageWithAttachments(msg: Message): Result<Message> = withSessionRetry {
        try {
            val token = tokenForMessage(msg) ?: return@withSessionRetry Result.failure(Exception("Nicht authentifiziert"))
            // Drafts have a separate detail endpoint that returns storageAttachments + full content
            val resp = if (msg.isDraft) {
                service().getDraftDetail(msg.id, token)
            } else {
                service().getMessageDetail(msg.id, token)
            }
            val raw = rawBody(resp) ?: return@withSessionRetry Result.success(msg)
            val detail: Message = parseJson(raw)
            val enriched = detail.copy(
                accountLabel = msg.accountLabel,
                storedIn     = msg.storedIn,
                replyHistory = detail.replyHistory
            )
            Result.success(enriched)
        } catch (e: Exception) {
            if (e is SessionExpiredException) throw e
            Result.success(msg) // fall back to original on error
        }
    }

    /**
     * Reads [body] into a byte array, invoking [onProgress] after every chunk with
     * (bytesReadSoFar, totalBytesOrMinusOneIfUnknown). Falls back to a plain one-shot read
     * when no progress callback is supplied.
     */
    private fun readBytesWithProgress(
        body: okhttp3.ResponseBody,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): ByteArray {
        if (onProgress == null) return body.bytes()
        val total = body.contentLength()
        val output = java.io.ByteArrayOutputStream(if (total > 0) total.toInt() else 8 * 1024)
        body.byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var bytesRead = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                bytesRead += read
                onProgress(bytesRead, total)
            }
        }
        return output.toByteArray()
    }

    suspend fun downloadAttachment(
        attachmentId: String,
        msg: Message,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Pair<ByteArray, String?>> = withSessionRetry {
        try {
            val token = tokenForMessage(msg) ?: return@withSessionRetry Result.failure(Exception("Nicht authentifiziert"))
            val urlResp = service().getAttachmentStorageUrl(attachmentId, token)
            val urlRaw = rawBody(urlResp) ?: return@withSessionRetry Result.failure(Exception("Keine Download-URL"))
            val storageUrl: AttachmentStorageUrl = parseJson(urlRaw)
            val downloadUrl = storageUrl.downloadUrl
                ?: return@withSessionRetry Result.failure(Exception("Download-URL fehlt"))
            val headers = storageUrl.additionalHeaders ?: emptyList()
            val encAlg = headers.firstOrNull { it.key == "x-amz-server-side-encryption-customer-algorithm" }?.value ?: ""
            val encKey = headers.firstOrNull { it.key == "x-amz-server-side-encryption-customer-key" }?.value ?: ""
            val encMd5 = headers.firstOrNull { it.key == "x-amz-server-side-encryption-customer-key-md5" }?.value ?: ""
            val dlResp = service().downloadFromStorage(downloadUrl, encAlg, encKey, encMd5)
            if (!dlResp.isSuccessful) {
                val errText = dlResp.errorBody()?.string()?.take(300)
                android.util.Log.w("WebUntis", "downloadFromStorage failed — HTTP ${dlResp.code()} url=$downloadUrl body=$errText")
                return@withSessionRetry Result.failure(Exception("Storage-Download fehlgeschlagen (HTTP ${dlResp.code()})"))
            }
            val body = dlResp.body() ?: return@withSessionRetry Result.failure(Exception("Keine Daten"))
            // The storage backend (S3) returns the ORIGINAL upload's Content-Type here — this is
            // far more reliable than guessing from the attachment's display name, which often has
            // no file extension at all (e.g. images named just by an internal id).
            val declaredMimeType = body.contentType()?.toString()
            val expectedLength = body.contentLength()
            val bytes = readBytesWithProgress(body, onProgress)
            android.util.Log.d("WebUntis", "downloadAttachment: expected=$expectedLength actual=${bytes.size} bytes")
            // Never silently "succeed" with an empty file — if the server told us how many bytes
            // to expect and we got fewer (or none), surface that as a real error instead of
            // letting the caller save/open a corrupt 0-byte file without any explanation.
            if (bytes.isEmpty() || (expectedLength > 0 && bytes.size.toLong() < expectedLength)) {
                return@withSessionRetry Result.failure(
                    Exception("Download unvollständig (${bytes.size} von ${if (expectedLength > 0) expectedLength else "?"} Bytes)")
                )
            }
            Result.success(bytes to declaredMimeType)
        } catch (e: Exception) {
            if (e is SessionExpiredException) throw e
            Result.failure(e)
        }
    }


    // ─── SENT MESSAGES ────────────────────────────────────────────────────────

    suspend fun getSentMessages(forceRefresh: Boolean = false): Result<List<Message>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheSentMessages },
        store = { cacheSentMessages = it },
    ) {
        try {
            val session      = sessionManager.session
            val primaryLabel = session?.personName?.takeIf { it.isNotBlank() } ?: session?.accountTypeLabel ?: "Hauptaccount"
            val token        = getAuthHeader() ?: return@withCacheOrFetch Result.failure(Exception("Nicht authentifiziert"))
            val primary      = fetchFolderMessages(token, "SENT", primaryLabel)
            val second       = sessionManager.secondAccount?.let { acc ->
                val server = session?.server ?: return@let emptyList<Message>()
                fetchFolderForSecondAccount(server, session.schoolname, acc.username, acc.password, acc.label, "SENT")
            } ?: emptyList()
            Result.success((primary + second).sortedByDescending { it.sentDateTimeForSorting })
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─── DRAFTS ───────────────────────────────────────────────────────────────

    suspend fun getDrafts(forceRefresh: Boolean = false): Result<List<Message>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheDraftMessages },
        store = { cacheDraftMessages = it },
    ) {
        try {
            val session      = sessionManager.session
            val primaryLabel = session?.personName?.takeIf { it.isNotBlank() } ?: session?.accountTypeLabel ?: "Hauptaccount"
            val token        = getAuthHeader() ?: return@withCacheOrFetch Result.failure(Exception("Nicht authentifiziert"))
            val primary      = fetchFolderMessages(token, "DRAFTS", primaryLabel)
            val second       = sessionManager.secondAccount?.let { acc ->
                val server = session?.server ?: return@let emptyList<Message>()
                fetchFolderForSecondAccount(server, session.schoolname, acc.username, acc.password, acc.label, "DRAFTS")
            } ?: emptyList()
            Result.success((primary + second).sortedByDescending { it.sentDateTimeForSorting })
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─── FOLDER HELPERS ───────────────────────────────────────────────────────

    private suspend fun fetchFolderMessages(token: String, folder: String, label: String): List<Message> {
        val resp = when (folder) {
            "SENT"   -> service().getSentMessagesAuth(token)
            "DRAFTS" -> service().getDraftsAuth(token)
            else     -> return emptyList()
        }
        val raw = rawBody(resp) ?: return emptyList()
        val jsonObj = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val arr = jsonObj.getAsJsonArray("sentMessages")
            ?: jsonObj.getAsJsonArray("draftMessages")
            ?: jsonObj.getAsJsonArray("outgoingMessages")
            ?: jsonObj.getAsJsonArray("incomingMessages")
            ?: return emptyList()
        val type = object : TypeToken<List<Message>>() {}.type
        val msgs: List<Message> = com.google.gson.Gson().fromJson(arr, type)
        val storedIn = if (folder == "DRAFTS") "DRAFT" else "SENT"
        return msgs.map { it.copy(accountLabel = label.takeIf { l -> l.isNotBlank() }, storedIn = storedIn) }
    }

    private suspend fun fetchFolderForSecondAccount(
        server: String, schoolname: String,
        username: String, password: String, label: String, folder: String
    ): List<Message> {
        return try {
            val svc = retrofitFactory.create(server)
            val loginBody = JsonRpcRequest(
                method = "authenticate",
                params = mapOf<String, Any>("user" to username, "password" to password, "client" to "android")
            )
            val loginRaw = svc.jsonRpcLogin(schoolname, loginBody).body()?.string()?.trim() ?: return emptyList()
            val rpcResp: JsonRpcResponse<AuthResult> =
                parseJson(loginRaw, object : TypeToken<JsonRpcResponse<AuthResult>>() {})
            rpcResp.result ?: return emptyList()
            val bearerRaw = svc.getBearerToken().body()?.string()?.trim() ?: return emptyList()
            val token = if (bearerRaw.startsWith("{"))
                com.google.gson.JsonParser.parseString(bearerRaw).asJsonObject.get("token")?.asString ?: bearerRaw
            else bearerRaw.trim('"')
            fetchFolderMessages("Bearer $token", folder, label)
        } catch (e: Exception) {
            android.util.Log.e("WebUntis", "2. Account – $folder fehlgeschlagen", e)
            emptyList()
        }
    }

    // ─── SEND MESSAGE ─────────────────────────────────────────────────────────

    suspend fun sendMessage(
        subject: String,
        content: String,
        recipientPersonIds: List<Int>,
        allowReply: Boolean = true,
        replyToMsgId: Int? = null,
        fromSecondAccount: Boolean = false
    ): Result<Unit> {
        return try {
            val token: String = if (fromSecondAccount) {
                val acc     = sessionManager.secondAccount ?: return Result.failure(Exception("Kein 2. Account"))
                val session = sessionManager.session       ?: return Result.failure(Exception("Nicht eingeloggt"))
                val svc     = retrofitFactory.create(session.server)
                val loginBody = JsonRpcRequest(
                    method = "authenticate",
                    params = mapOf<String, Any>("user" to acc.username, "password" to acc.password, "client" to "android")
                )
                svc.jsonRpcLogin(session.schoolname, loginBody)
                val bearerRaw = svc.getBearerToken().body()?.string()?.trim()
                    ?: return Result.failure(Exception("Kein Token"))
                val raw = if (bearerRaw.startsWith("{"))
                    com.google.gson.JsonParser.parseString(bearerRaw).asJsonObject.get("token")?.asString ?: bearerRaw
                else bearerRaw.trim('"')
                "Bearer $raw"
            } else {
                getAuthHeader() ?: return Result.failure(Exception("Nicht authentifiziert"))
            }

            val gson    = com.google.gson.Gson()
            val payload = buildString {
                append("{")
                append("\"subject\":${gson.toJson(subject)},")
                append("\"content\":${gson.toJson(content)},")
                append("\"recipientPersonIds\":${gson.toJson(recipientPersonIds)},")
                append("\"allowReply\":$allowReply")
                if (replyToMsgId != null) append(",\"replyToMessageId\":$replyToMsgId")
                append("}")
            }
            val reqBody =
                payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            service().sendMessage(token, reqBody)
            // Invalidate inbox + sent caches
            cacheMessages = null; cacheSentMessages = null
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─── SAVE / UPDATE DRAFT ─────────────────────────────────────────────────

    /**
     * Creates a new draft (draftId == null) or updates an existing one.
     * [recipientPersonIds] may be empty while composing.
     * Returns the saved draft [Message] on success.
     */
    suspend fun saveDraft(
        subject: String,
        content: String,
        recipientPersonIds: List<Int> = emptyList(),
        draftId: Int? = null,
        fromSecondAccount: Boolean = false,
        attachments: List<Pair<String, ByteArray>> = emptyList(),   // new files: filename → bytes
        removedAttachmentIds: List<String> = emptyList()            // existing storage IDs to delete
    ): Result<Message> {
        return try {
            val token: String = if (fromSecondAccount) {
                val acc     = sessionManager.secondAccount ?: return Result.failure(Exception("Kein 2. Account"))
                val session = sessionManager.session       ?: return Result.failure(Exception("Nicht eingeloggt"))
                val svc     = retrofitFactory.create(session.server)
                val loginBody = JsonRpcRequest(
                    method = "authenticate",
                    params = mapOf<String, Any>("user" to acc.username, "password" to acc.password, "client" to "android"))
                svc.jsonRpcLogin(session.schoolname, loginBody)
                val bearerRaw = svc.getBearerToken().body()?.string()?.trim()
                    ?: return Result.failure(Exception("Kein Token"))
                val raw = if (bearerRaw.startsWith("{"))
                    com.google.gson.JsonParser.parseString(bearerRaw).asJsonObject.get("token")?.asString ?: bearerRaw
                else bearerRaw.trim('"')
                "Bearer $raw"
            } else {
                getAuthHeader() ?: return Result.failure(Exception("Nicht authentifiziert"))
            }

            val gson = com.google.gson.Gson()
            val requestJson = gson.toJson(
                com.webuntis.dashboard.model.SaveDraftRequest(
                    subject = subject,
                    content = content,
                    hasAttachments = attachments.isNotEmpty() || removedAttachmentIds.isEmpty(),
                    attachmentIdsToDelete = removedAttachmentIds
                )
            )
            val requestPart = okhttp3.MultipartBody.Part.createFormData(
                "request", "blob",
                requestJson.toRequestBody("application/json".toMediaTypeOrNull())
            )
            val attachmentParts = attachments.map { (filename, bytes) ->
                val mimeType = mimeTypeForFilename(filename)
                okhttp3.MultipartBody.Part.createFormData(
                    "attachments", filename,
                    bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                )
            }

            val resp = if (draftId == null) {
                service().saveDraft(token, requestPart, attachmentParts)
            } else {
                service().updateDraft(token, draftId, requestPart, attachmentParts)
            }
            val responseRaw = rawBody(resp)
                ?: return Result.failure(Exception("Leere Antwort vom Server"))
            val saved: Message = gson.fromJson(responseRaw, Message::class.java)
            cacheDraftMessages = null
            Result.success(saved.copy(storedIn = "DRAFT"))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun mimeTypeForFilename(filename: String): String = when {
        filename.endsWith(".pdf",  true) -> "application/pdf"
        filename.endsWith(".png",  true) -> "image/png"
        filename.endsWith(".jpg",  true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
        filename.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        filename.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        filename.endsWith(".txt",  true) -> "text/plain"
        else -> "application/octet-stream"
    }

    // ─── DELETE MESSAGE / DRAFT ───────────────────────────────────────────────

    suspend fun deleteMessage(msg: Message): Result<Unit> {
        return try {
            val token = tokenForMessage(msg) ?: return Result.failure(Exception("Nicht authentifiziert"))
            service().deleteMessage(token, msg.id)
            when {
                msg.isDraft -> cacheDraftMessages = null
                msg.isSent  -> cacheSentMessages  = null
                else        -> cacheMessages      = null
            }
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─── TEACHERS ─────────────────────────────────────────────────────────────

    suspend fun getTeachers(forceRefresh: Boolean = false): Result<List<com.webuntis.dashboard.model.RecipientPerson>> {
        cacheTeachers?.takeIf { !forceRefresh }?.let { return Result.success(it) }
        return try {
            val token = getAuthHeader() ?: return Result.failure(Exception("Nicht authentifiziert"))
            val resp  = service().getMessageRecipientsAuth(token)
            val raw   = rawBody(resp) ?: return Result.success(emptyList())
            // Response is a JSON array of { "type": "TEACHERS"|"CLASS_TEACHERS"|"OTHERS", "persons": [...] }
            val groupType = object : TypeToken<List<com.webuntis.dashboard.model.RecipientGroup>>() {}.type
            val groups: List<com.webuntis.dashboard.model.RecipientGroup> =
                com.google.gson.Gson().fromJson(raw, groupType)
            // Merge CLASS_TEACHERS first, then TEACHERS, deduplicate by userId
            val seenIds = mutableSetOf<Int>()
            val persons = (groups.filter { it.type == "CLASS_TEACHERS" } +
                           groups.filter { it.type == "TEACHERS" } +
                           groups.filter { it.type == "OTHERS" })
                .flatMap { it.persons ?: emptyList() }
                .filter { seenIds.add(it.userId) }
                .sortedBy { it.displayName ?: "" }
            cacheTeachers = persons
            Result.success(persons)
        } catch (e: Exception) { Result.failure(e) }
    }

        /**
     * Verifies the given credentials against the WebUntis server and, if successful,
     * saves them as the second account in [SessionManager].
     * Returns a human-readable summary string for the UI on success.
     */
    suspend fun verifyAndSaveSecondAccount(username: String, password: String, label: String): Result<String> {
        // session should always be present; if not, try re-login from persisted credentials
        if (sessionManager.session == null) {
            val creds  = sessionManager.storedCredentials
            val stored = sessionManager.storedSessionMeta
            if (creds != null && stored != null) {
                login(stored.first, stored.second, creds.first, creds.second)
            }
        }
        val session = sessionManager.session
            ?: return Result.failure(Exception("Nicht angemeldet — bitte zuerst den Hauptaccount speichern"))
        return try {
            val rpc = loginViaJsonRpc(session.server, session.schoolname, username, password)
            val result = if (rpc.isSuccess) rpc else loginViaRest(session.server, session.schoolname, username, password)
            result.fold(
                onSuccess = { sessionData ->
                    val second = SessionManager.SecondAccount(
                        username   = username,
                        password   = password,
                        label      = label.trim(),
                        personType = sessionData.personType,
                        personName = sessionData.personName
                    )
                    sessionManager.secondAccount = second
                    // Restore primary session so the main account stays logged in
                    login(session.server, session.schoolname,
                        sessionManager.storedCredentials?.first ?: session.username,
                        sessionManager.storedCredentials?.second ?: "")
                    val info = buildString {
                        if (second.personName.isNotBlank()) append(second.personName)
                        if (second.accountTypeLabel.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(second.accountTypeLabel)
                        }
                        if (second.label.isNotBlank()) {
                            if (isNotEmpty()) append(" (")
                            append(second.label)
                            append(")")
                        }
                        if (isEmpty()) append(username)
                    }
                    Result.success(info)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) { Result.failure(e) }
    }
}

data class TimetableDay(val date: LocalDate, val lessons: List<Lesson>) {
    val isToday: Boolean    get() = date == LocalDate.now()
    val isTomorrow: Boolean get() = date == LocalDate.now().plusDays(1)
    val label: String get() = when { isToday -> "Heute"; isTomorrow -> "Morgen"; else -> date.format(DateTimeFormatter.ofPattern("EEE dd.MM.", java.util.Locale.GERMAN)) }
}
