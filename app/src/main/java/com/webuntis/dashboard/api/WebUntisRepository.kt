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
import java.time.temporal.ChronoUnit
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

    private val additionalBearerTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val additionalBearerTokenFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * How long a child account's bearer token is reused before logging in again. Kept fairly
     * short (unlike the primary's, which is refreshed reactively on 401/expiry) because a
     * stale child token failing deep inside, say, per-lesson enrichment wouldn't necessarily
     * surface as a clean retry — capping reuse time bounds how long that risk window stays
     * open, while still avoiding a full extra login for every single nested call.
     */
    private val ADDITIONAL_TOKEN_TTL_MS = 5 * 60 * 1000L

    /**
     * Resolves the Bearer header to use for whichever account is currently active for
     * browsing (see SessionManager.activeAccount / the account switcher). This is the ONE
     * place nearly every read function in this file gets its auth header from, so making it
     * account-aware here is what makes the switcher affect timetable/absences/homework/
     * classbook/events without having to individually thread an account parameter through
     * every one of those functions (and their own nested helpers) by hand.
     */
    private suspend fun getAuthHeader(): String? {
        val active = sessionManager.activeAccount ?: run {
            val token = bearerToken ?: fetchBearerToken().getOrNull()
            return token?.let { "Bearer $it" }
        }
        val fetchedAt = additionalBearerTokenFetchedAt[active.key]
        val cached = additionalBearerTokens[active.key]
        if (cached != null && fetchedAt != null && System.currentTimeMillis() - fetchedAt < ADDITIONAL_TOKEN_TTL_MS) {
            return "Bearer $cached"
        }
        val session = sessionManager.session ?: return null
        val token = loginAdditionalAccount(session.server, session.schoolname, active) ?: return null
        additionalBearerTokenFetchedAt[active.key] = System.currentTimeMillis()
        return "Bearer $token"
    }

    /**
     * Performs a full re-authentication.
     * @param force If true, skips the session age check (essential for recovery flows).
     */
    private suspend fun reAuthSilently(
        server: String, schoolname: String, username: String, password: String, force: Boolean = false
    ): Result<SessionData> = loginMutex.withLock {
        try {
            // Double check: if someone else refreshed while we waited for the lock
            if (sessionManager.isSessionFresh() && !force) {
                return@withLock sessionManager.session?.let { Result.success(it) }
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
                fetchCsrfToken() // needed before any write request (create/update/delete absence etc.)

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
                return@withLock resolvedSession?.let { Result.success(it) } ?: result
            } else {
                android.util.Log.e("WebUntis", "Silent re-auth FAILED: ${result.exceptionOrNull()?.message}")
            }
            result
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception: a release-build (R8) LinkageError/NoSuchMethodError
            // here would otherwise bypass this entirely and surface only as a bare, message-less
            // "Fehler" toast further up — always give the caller a real message to show/log.
            android.util.Log.e("WebUntis", "Silent re-auth threw unexpectedly: ${t.javaClass.simpleName}: ${t.message}", t)
            Result.failure(Exception("Re-Login fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}"))
        }
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
            if (sessionManager.activeAccount == null) reLoginIfNeeded()
            val result = block()

            val error = result.exceptionOrNull()
            if (error is SessionExpiredException ||
                (error != null && (error.message?.contains("-32001") == true || error.message?.contains("Session abgelaufen") == true))) {
                throw SessionExpiredException(error.message ?: "Session abgelaufen")
            }

            if (result.isSuccess) sessionManager.touchSession()
            result
        } catch (e: SessionExpiredException) {
            val active = sessionManager.activeAccount
            if (active != null) {
                // A child account's token expired/was rejected. Refresh THAT SAME child and
                // retry — deliberately never falls through to the primary-account re-auth
                // below, which would silently re-authenticate as the wrong account (exactly
                // the class of bug already found once in the second-account message flow).
                android.util.Log.i("WebUntis", "Session expired for additional account '${active.label.ifBlank { active.username }}' mid-request — re-logging in as that account and retrying")
                additionalBearerTokens.remove(active.key)
                additionalBearerTokenFetchedAt.remove(active.key)
                val session = sessionManager.session ?: return Result.failure(e)
                val fresh = loginAdditionalAccount(session.server, session.schoolname, active)
                if (fresh == null) return Result.failure(e)
                return try {
                    block()
                } catch (t2: Throwable) {
                    android.util.Log.e("WebUntis", "withSessionRetry (additional account): retry FAILED — ${t2.javaClass.name}: ${t2.message}", t2)
                    Result.failure(Exception("${t2.javaClass.simpleName}: ${t2.message ?: "unbekannter Fehler beim Wiederholen"}"))
                }
            }
            android.util.Log.i("WebUntis", "Session expired mid-request — attempting silent re-auth and retry")
            val creds   = sessionManager.storedCredentials
            val session = sessionManager.session
            if (creds == null || session == null) return Result.failure(e)

            val reAuth = reAuthSilently(session.server, session.schoolname, creds.first, creds.second, force = true)
            if (reAuth.isFailure) return Result.failure(reAuth.exceptionOrNull() ?: e)

            try {
                val retry = block()
                if (retry.isSuccess) sessionManager.touchSession()
                retry
            } catch (t2: Throwable) {
                android.util.Log.e("WebUntis", "withSessionRetry: retry after re-auth FAILED — ${t2.javaClass.name}: ${t2.message}", t2)
                Result.failure(Exception("${t2.javaClass.simpleName}: ${t2.message ?: "unbekannter Fehler beim Wiederholen"}"))
            }
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception: guarantees a logged, non-null-message failure
            // for literally anything that can go wrong here (including reLoginIfNeeded/reAuthSilently
            // internals and any release-build-only LinkageError/NoSuchMethodError from R8).
            android.util.Log.e("WebUntis", "withSessionRetry: UNEXPECTED ${t.javaClass.name}: ${t.message}", t)
            Result.failure(Exception("${t.javaClass.simpleName}: ${t.message ?: "unbekannter Fehler"}"))
        }
    }

    private data class CacheEntry<T>(val fetchedAt: Long, val data: Result<T>)

    /** Cache key for whichever account is currently active for browsing (see
     *  SessionManager.activeAccount / the account switcher) — "primary" for the main account. */
    private fun currentAccountScope(): String = sessionManager.activeAccountKey ?: "primary"

    // Per-student data — scoped by account, so switching the active child doesn't show a
    // stale/mixed result left over from whoever was active before (see currentAccountScope()).
    private var cacheTimetableByAccount:    MutableMap<String, CacheEntry<List<TimetableDay>>> = mutableMapOf()
    private var cacheHomeworkByAccount:     MutableMap<String, CacheEntry<Pair<List<Homework>, Map<String, String>>>> = mutableMapOf()
    private var cacheEventsByAccount:       MutableMap<String, CacheEntry<List<SchoolEvent>>> = mutableMapOf()
    private var cacheClassbookByAccount:    MutableMap<String, CacheEntry<List<ClassbookEntry>>> = mutableMapOf()
    // Holds both the reported absences AND their per-lesson breakdown, since both come from
    // the single classreg/absencetimes/student call — see fetchAbsencesAndTimes().
    private var cacheAbsencesByAccount:     MutableMap<String, CacheEntry<Pair<List<Absence>, List<AbsenceTime>>>> = mutableMapOf()
    private var cacheAbsencesMetaByAccount: MutableMap<String, CacheEntry<AbsencesMetaData>> = mutableMapOf()

    // School-wide data (not per-student) — stays a single shared cache regardless of which
    // account is active.
    private var cacheSchoolYear:   CacheEntry<List<com.webuntis.dashboard.model.SchoolYearInfo>>? = null
    private var cacheMessages:     CacheEntry<List<Message>>?                             = null
    private var cacheSentMessages: CacheEntry<List<Message>>?                             = null
    private var cacheDraftMessages:CacheEntry<List<Message>>?                             = null
    private var cacheTeachers:     List<com.webuntis.dashboard.model.RecipientPerson>?   = null
    private var cacheTimegrid:     CacheEntry<List<com.webuntis.dashboard.model.TimegridRow>>? = null
    // "Unterrichtsinhalte" tab: incrementally-grown cache of Lesson.teachingContent entries.
    // Unlike the other cache* fields this isn't a single CacheEntry, because the tab's window
    // size grows over time ("Weitere Tage laden") and each grow only needs to fetch+enrich the
    // newly-uncovered older slice instead of re-fetching (and re-hitting the per-lesson detail
    // endpoint for) days already covered. See getTeachingContentEntries().
    private var cacheTeachingContentDays:    Int          = 0
    private var cacheTeachingContentEntries: List<Lesson> = emptyList()

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
        cacheTimetableByAccount.clear(); cacheHomeworkByAccount.clear(); cacheEventsByAccount.clear()
        cacheClassbookByAccount.clear(); cacheSchoolYear = null; cacheAbsencesByAccount.clear(); cacheMessages = null; cacheSentMessages = null; cacheDraftMessages = null; cacheTeachers = null
        cacheTeachingContentDays = 0; cacheTeachingContentEntries = emptyList()
        cacheAbsencesMetaByAccount.clear(); cacheTimegrid = null
    }

    /** Switches between the personal ("MY_TIMETABLE") and class ("STANDARD") timetable views. */
    fun setTimetableViewMode(mode: SessionManager.TimetableViewMode) {
        if (sessionManager.timetableViewMode == mode) return
        sessionManager.timetableViewMode = mode
        cacheTimetableByAccount.clear()
    }

    /** Updates which class-plan subjects are allowed to fill gaps in COMBINED mode and refreshes. */
    fun setCombinedOverlaySubjects(subjects: Set<String>) {
        if (sessionManager.combinedOverlaySubjects == subjects) return
        sessionManager.combinedOverlaySubjects = subjects
        cacheTimetableByAccount.clear()
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

    fun isHomeworkCacheFresh():  Boolean { val e = cacheHomeworkByAccount[currentAccountScope()]  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }

    /**
     * Builds a short↔long lookup for subjects and teachers from whatever timetable data is
     * currently cached (falls back to a small fresh fetch if nothing is cached yet). Homework,
     * events and messages each carry only a short code or only a long name from their own
     * endpoints — this lets the UI show both, e.g. "Mathematik (M)" / "Müller (Mü)".
     */
    suspend fun getNameCatalog(): NameCatalog {
        val cachedLessons = cacheTimetableByAccount[currentAccountScope()]?.data?.getOrNull()?.flatMap { it.lessons }
        val lessons = if (!cachedLessons.isNullOrEmpty()) cachedLessons else {
            val start = LocalDate.now().minusDays(7).toUntis()
            val end   = LocalDate.now().plusDays(14).toUntis()
            fetchLessonsInRange(start, end).getOrNull() ?: emptyList()
        }
        val subjectMap = linkedMapOf<String, String>()
        val teacherMap = linkedMapOf<String, String>()
        val colorMap = linkedMapOf<String, String>()
        lessons.forEach { l ->
            val subjShort = l.subjectName.takeIf { it.isNotBlank() && it != "–" }
            val subjLong  = l.subjectLongName.takeIf { it.isNotBlank() && it != "–" }
            if (subjShort != null && subjLong != null && subjectMap[subjShort].isNullOrBlank()) {
                subjectMap[subjShort] = subjLong
            }
            val subjColor = l.color?.takeIf { it.isNotBlank() }
            if (subjShort != null && subjColor != null && colorMap[subjShort].isNullOrBlank()) {
                colorMap[subjShort] = subjColor
            }
            l.te?.forEach { t ->
                val short = t.name?.takeIf { it.isNotBlank() }
                val long  = t.longname?.takeIf { it.isNotBlank() }
                if (short != null && long != null && teacherMap[short].isNullOrBlank()) {
                    teacherMap[short] = long
                }
            }
        }
        return NameCatalog(subjectMap, teacherMap, colorMap)
    }
    fun isEventsCacheFresh():    Boolean { val e = cacheEventsByAccount[currentAccountScope()]    ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isClassbookCacheFresh(): Boolean { val e = cacheClassbookByAccount[currentAccountScope()] ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isAbsencesCacheFresh():  Boolean { val e = cacheAbsencesByAccount[currentAccountScope()]  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isMessagesCacheFresh():  Boolean { val e = cacheMessages  ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }
    fun isTimetableCacheFresh(): Boolean { val e = cacheTimetableByAccount[currentAccountScope()] ?: return false; return sessionManager.isCacheFresh(e.fetchedAt) }

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
            val url = r.raw().request.url
            android.util.Log.w("WebUntis", "Request failed — HTTP ${r.code()} ${url.encodedPath}")
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

    /** Extracts the tenant_id claim from the current JWT bearer token (base64url decode of
     *  payload). WebUntis write endpoints (create/update/delete absence) 403 without this, so
     *  as a defensive measure against the claim key varying between deployments, several
     *  spellings are tried, and the last value that resolved is cached (see
     *  SessionManager.cachedTenantId) so a transient decode hiccup doesn't lose it entirely. */
    private fun tenantIdFromToken(): String? {
        val token = bearerToken
        val fromToken = token?.let {
            try {
                val payload = it.split(".").getOrNull(1) ?: return@let null
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                val json = String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE))
                val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
                obj.get("tenant_id")?.asString
                    ?: obj.get("tenantId")?.asString
                    ?: obj.get("tenantid")?.asString
            } catch (e: Exception) { null }
        }
        if (!fromToken.isNullOrBlank()) {
            sessionManager.cachedTenantId = fromToken
            return fromToken
        }
        // JWT didn't have it (or there's no token yet) — fall back to whatever we last
        // resolved successfully, rather than sending no Tenant-Id header at all.
        return sessionManager.cachedTenantId
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

    /**
     * Fetches the CSRF token the WebUntis backend requires on state-changing requests
     * (create/update/delete absence etc.) once a session is established. Unlike the bearer
     * token, WebUntis never exposes this via a JSON API or a cookie — the official web client
     * only ever gets it embedded in a `dojoConfig = {...}` script blob inside the HTML of the
     * "embedded" shell page (`grupet.csrfToken` / `grupet.csrfHeader`, currently
     * "X-CSRF-TOKEN"). So we fetch that same page and pull the token out with a regex instead
     * of parsing the page as HTML/JS.
     *
     * Deliberately non-fatal: if this fails, login itself still succeeds — only the write
     * features (submitting an absence etc.) would then still hit the 403 this is meant to
     * prevent, exactly as before this fix existed.
     */
    private suspend fun fetchCsrfToken(): Result<String?> {
        return try {
            val resp = service().getEmbeddedPage()
            if (resp.headers()["X-WebUntis-Session-Expired"] == "true" || resp.code() == 401) {
                throw SessionExpiredException()
            }
            // Deliberately NOT using the shared rawBody() here: it unconditionally treats any
            // successful response whose body starts with "<html"/"<!DOCTYPE" as a session-expiry
            // redirect — correct for the JSON API endpoints it's normally used for, but wrong
            // here, since embedded.do's body IS HTML on every successful call (that's the whole
            // point of scraping it for the CSRF token). Applying that same rule here made this
            // fetch throw SessionExpiredException 100% of the time, valid session or not, which
            // is why csrfToken ended up permanently MISSING and every absence write 403'd no
            // matter how "fresh" the login was.
            val html = (if (resp.isSuccessful) resp.body()?.string() else resp.errorBody()?.string())
                ?: return Result.success(null)
            // A genuine expired-session redirect for this page lands on the plain login/anonymous
            // shell instead of the real embedded page — that page has no dojoConfig/grupet user
            // data in it, unlike a normal successful response (see the sample HTML this was
            // written against).
            if (!resp.isSuccessful || (!html.contains("dojoConfig") &&
                    (html.contains("login.do", ignoreCase = true) || html.contains("index.do", ignoreCase = true)))) {
                throw SessionExpiredException()
            }
            val token = Regex("\"csrfToken\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            if (token.isNullOrBlank()) {
                android.util.Log.w("WebUntis", "fetchCsrfToken: embedded.do returned OK but no csrfToken found in dojoConfig (page may have changed)")
            }
            sessionManager.csrfToken = token
            Result.success(token)
        } catch (e: Exception) {
            android.util.Log.w("WebUntis", "fetchCsrfToken failed (write requests like creating an absence may 403 until next login): ${e.message}")
            Result.failure(e)
        }
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
            // Needed before any write request (create/update/delete absence etc.) — fetched
            // proactively here (not just lazily via ensureCsrfToken() on first use) so a fresh
            // login/app start already has it ready, same as reAuthSilently() does.
            fetchCsrfToken()
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
                personType = data.person?.type ?: 0,
                className = data.schoolyearData?.klasse?.name?.takeIf { it.isNotBlank() }
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
        startDate: String, endDate: String, anchorDate: LocalDate = LocalDate.now(),
        maxEnrich: Int = 40, anchorRangeEnd: LocalDate = anchorDate
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
                        fetchLessonsV1(startIso, endIso, effectiveClassId, "STUDENT", "MY_TIMETABLE", 5, anchorDate, maxEnrich, anchorRangeEnd)
                    }
                    val classDeferred = async {
                        fetchLessonsV1(startIso, endIso, classElementId, "CLASS", "STANDARD", 1, anchorDate, maxEnrich, anchorRangeEnd)
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
            return fetchLessonsV1(startIso, endIso, elementId, resourceType, timetableType, elementType, anchorDate, maxEnrich, anchorRangeEnd)
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
    /**
     * Opportunistically caches the school's own resolved class short name (e.g. "8c") into
     * the session whenever a CLASS-resource response comes back — from an explicit Klassenplan
     * fetch, or the COMBINED-mode class-plan overlay fetch. This self-heals sessions that
     * predate `SessionData.className` being captured at login (it's only set on a fresh REST
     * login), so "add my own class back" in [TimetableV1Entry.toLesson] starts working for the
     * PERSONAL/STUDENT view too as soon as any class-mode fetch has happened once — no
     * re-login required. No-op once the cached name already matches.
     */
    private fun cacheOwnClassNameIfNeeded(ttResp: TimetableV1Response) {
        val resolvedClassName = ttResp.days
            ?.firstOrNull { it.resourceType == "CLASS" }
            ?.resource?.shortName?.takeIf { it.isNotBlank() }
            ?: return
        val current = sessionManager.session ?: return
        if (current.className != resolvedClassName) {
            sessionManager.session = current.copy(className = resolvedClassName)
        }
    }

    private suspend fun fetchLessonsV1(
        startIso: String, endIso: String, elementId: Int,
        resourceType: String, timetableType: String, elementType: Int,
        anchorDate: LocalDate = LocalDate.now(),
        maxEnrich: Int = 40,
        anchorRangeEnd: LocalDate = anchorDate
    ): Result<List<Lesson>> {
        return try {
            val response = callTimetableV1(
                startIso, endIso, elementId,
                resourceType = resourceType, timetableType = timetableType
            )
            val raw = rawBody(response) ?: return Result.success(emptyList())
            val ttResp: TimetableV1Response = parseJson(raw)
            cacheOwnClassNameIfNeeded(ttResp)
            val lessons = ttResp.toLessons(sessionManager.session?.className)
            val enriched = enrichLessonsWithDetail(lessons, elementId, elementType, anchorDate, anchorRangeEnd, maxEnrich)
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
        cache = { cacheTimetableByAccount[currentAccountScope()] },
        store = { cacheTimetableByAccount[currentAccountScope()] = it },
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
     *
     * [maxEnrich] is exposed (rather than always defaulting) so callers that only need to know
     * *which dates* have lessons — not their content — can pass 0 and skip the per-lesson
     * CalendarEntryDetail calls entirely. See findSchoolDayOffset() in TimetableViewModel.
     */
    suspend fun getSchoolDaysFrom(
        anchorDate: LocalDate,
        numDays: Int,
        forceRefresh: Boolean = false,
        maxEnrich: Int = 40
    ): Result<List<TimetableDay>> {
        // Fetch a wide enough window — ±60 days covers holidays and long breaks
        val windowStart = anchorDate.minusDays(if (anchorDate.isBefore(LocalDate.now())) 60 else 0)
        val windowEnd   = anchorDate.plusDays(60)
        // The enrichment budget (see enrichLessonsWithDetail) is prioritized by distance to a
        // date *range*, not a single point: anchorDate alone is only the OLDEST edge of the
        // numDays window actually being requested here, so a single-point anchor would starve
        // out the NEWEST day in that window (e.g. "yesterday" when loading the last few past
        // days) in favour of unrelated lessons that happen to sit just as far on the other side
        // of anchorDate but aren't even part of what's being shown. Since real school days can
        // skip weekends/holidays, numDays school days can span more than numDays calendar days —
        // *2 plus a two-week buffer comfortably covers normal breaks without widening the range
        // so much it stops being a meaningful priority signal.
        val rangeEnd = anchorDate.plusDays((numDays.toLong() * 2) + 14)
        val rangeResult = fetchLessonsInRange(windowStart.toUntis(), windowEnd.toUntis(), anchorDate, maxEnrich, rangeEnd)
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
     * Merges cancelled+active lesson pairs into a single "statt [Old Subject]" entry, using
     * [mergeOverlappingLessons]'s layoutGroup-based grouping — which only folds a shared time
     * slot together when there's a genuine sign of replacement (an EVENT entry, or at least
     * one entry the API itself marked CANCELLED), so unrelated parallel courses sharing a
     * slot (differentiated groups, religion vs. ethics, electives) are correctly left
     * separate. This is now safe for both PERSONAL and CLASS mode — CLASS mode used to skip
     * merging entirely (relying purely on time-overlap here risked attaching a "statt" label
     * to an unrelated parallel course), but layoutGroup removes that ambiguity.
     */
    private fun mergeLessonsForCurrentView(lessons: List<Lesson>): List<Lesson> {
        // In COMBINED mode, only merge cancelled/active pairs among the personal-plan lessons —
        // overlay entries filled in from the class plan can include several unrelated parallel
        // class-plan subjects and must never be paired into a false "statt" substitution.
        val overlay = lessons.filter { it.isFromClassPlan }
        if (overlay.isEmpty()) return mergeOverlappingLessons(lessons)
        val personalOnly = lessons.filter { !it.isFromClassPlan }
        return mergeOverlappingLessons(personalOnly) + overlay
    }

    /**
     * Groups lessons that share the same [Lesson.layoutGroup] on the same day — the exact
     * row-layout hint WebUntis's own web client uses to say "these entries occupy the same
     * displayed time slot" — into a single displayed entry with "statt ..." substitute info,
     * picking whichever one actually happens as the primary.
     *
     * This used to be inferred purely from time overlap + each lesson's OWN cancelled flag,
     * which broke for a lesson pulled from a shared/differentiated course: its own top-level
     * `status` stays "CHANGED" (the course still runs for the other classes), so it doesn't
     * individually look cancelled — the merge would then leave it stranded outside the group,
     * competing with the real replacement entry (e.g. a workshop EVENT) for the same slot
     * instead of being folded into it. layoutGroup sidesteps that: WebUntis already decided
     * which entries share this slot, so if a group also contains a genuine sign of
     * replacement (an `EVENT` entry, or at least one entry the API itself marked
     * `CANCELLED`), every OTHER entry in that group gets folded in regardless of its own
     * individual status — it doesn't need to be individually recognized as cancelled.
     *
     * Groups with no such sign (e.g. two unrelated parallel elective courses sharing a slot,
     * both genuinely happening) are left untouched — they're meant to be shown side by side,
     * not merged. Lessons without layoutGroup info fall back to the previous time-overlap
     * pairing (defensive — should be rare).
     */
    private fun mergeOverlappingLessons(lessons: List<Lesson>): List<Lesson> {
        if (lessons.size < 2) return lessons

        val (withGroup, withoutGroup) = lessons.partition { it.layoutGroup != null }
        val result = mutableListOf<Lesson>()

        withGroup.groupBy { it.date to it.layoutGroup }.values.forEach { group ->
            if (group.size < 2) {
                result.addAll(group)
                return@forEach
            }
            val hasEvent = group.any { it.isEventType }
            val hasExplicitlyCancelled = group.any { it.isCancelled }
            if (!hasEvent && !hasExplicitlyCancelled) {
                // No sign anything here was actually replaced — genuinely-parallel active
                // offerings (e.g. religion vs. ethics), keep them all separate.
                result.addAll(group)
                return@forEach
            }
            val primary = group.firstOrNull { it.isEventType }
                ?: group.firstOrNull { !it.isCancelled }
                ?: run { result.addAll(group); return@forEach } // all cancelled, nothing to attach "statt" to
            val others = group.filterNot { it === primary }
            val insteadOf = others.map { it.subjectName }.filter { it.isNotBlank() && it != "–" }.distinct().joinToString(", ")
            val replacedTeachers = others.flatMap { o ->
                o.te?.mapNotNull { it.longname ?: it.name } ?: emptyList<String>()
            }.distinct()
            val combinedRemoved = ((primary.removedTeachers ?: emptyList<String>()) + replacedTeachers).distinct()
            val newLstype = if (primary.lstype == null || primary.lstype == "ls") "subst" else primary.lstype
            result.add(primary.copy(
                replacedSubject = insteadOf.ifBlank { null },
                lstype = newLstype,
                removedTeachers = combinedRemoved.ifEmpty { null }
            ))
        }

        result.addAll(mergeOverlappingLessonsByTimeOverlap(withoutGroup))
        return result.sortedBy { it.startTime }
    }

    /** Fallback pairing for lessons without [Lesson.layoutGroup] info — pairs each active
     *  lesson with any cancelled lesson overlapping its time range. See
     *  [mergeOverlappingLessons] for the preferred, more reliable layoutGroup-based path. */
    private fun mergeOverlappingLessonsByTimeOverlap(lessons: List<Lesson>): List<Lesson> {
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
        elementType: Int,
        anchorDate: LocalDate = LocalDate.now(),
        anchorRangeEnd: LocalDate = anchorDate,
        maxEnrich: Int = 40
    ): List<Lesson> {
        val token = getAuthHeader() ?: return lessons
        // fetchLessonsInRange can cover a wide prefetch window (getSchoolDaysFrom uses up to
        // ±60 days for smooth pagination), but detail-enriching every lesson in it would mean
        // hundreds of extra per-lesson network calls. So only a bounded number are enriched —
        // but crucially, prioritized by closeness to the days actually being viewed
        // ([anchorDate, anchorRangeEnd]), NOT just "however they happen to be ordered in the
        // array". Previously this used a plain lessons.take(40), which silently favoured
        // whichever end of the range came first — fine when viewing today (the window starts at
        // today), but when navigating to a PAST day, the window extends up to 60 days further
        // back, pushing the actually-requested day itself past the cutoff. That's why
        // homework/Unterrichtsstoff only ever showed up for "current" days: past-day lessons
        // were simply never enriched at all.
        //
        // Using a single anchorDate *point* (distance-to-anchorDate) fixed that but introduced a
        // subtler version of the same bug: when loading e.g. "the last 5 days", the anchor is the
        // OLDEST day of that 5-day window (so the ±60-day search range can be positioned), which
        // means the NEWEST day in that same window — typically "yesterday", the one closest to
        // today — sits furthest from the anchor point. Days on the *other* side of the anchor
        // (i.e. even further into the past, outside the window and never shown) are equally far
        // and compete for the same 40-lesson budget, so "yesterday" can lose out to lessons that
        // aren't even displayed. Scoring by distance to the [anchorDate, anchorRangeEnd] *range*
        // instead of a single point gives every day actually being displayed a distance of 0,
        // so they're never out-competed by padding lessons outside the requested window.
        val needsDetail = lessons
            .sortedBy { lesson ->
                val d = untisIntToDate(lesson.date)
                when {
                    !d.isBefore(anchorDate) && !d.isAfter(anchorRangeEnd) -> 0L
                    d.isBefore(anchorDate) -> ChronoUnit.DAYS.between(d, anchorDate)
                    else -> ChronoUnit.DAYS.between(anchorRangeEnd, d)
                }
            }
            .take(maxEnrich)
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
            val newColor = lesson.color?.takeIf { it.isNotBlank() }
                ?: detail.color?.takeIf { it.isNotBlank() }

            lesson.copy(
                substText           = newSubst,
                info                = newInfo,
                teachingContent     = newTeachingContent,
                removedTeachers     = mergedRemoved,
                substitutedTeachers = substituted,
                code                = newCode,
                lstype              = newLstype,
                color               = newColor
            )
        }
    }

    suspend fun getHomework(forceRefresh: Boolean = false): Result<Pair<List<Homework>, Map<String, String>>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheHomeworkByAccount[currentAccountScope()] },
        store = { cacheHomeworkByAccount[currentAccountScope()] = it },
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
            // Real blocking network I/O — must not run on whatever thread called us.
            val bytes = withContext(Dispatchers.IO) { readBytesWithProgress(body, onProgress) }
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
        cache = { cacheClassbookByAccount[currentAccountScope()] },
        store = { cacheClassbookByAccount[currentAccountScope()] = it },
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

    /**
     * Returns lessons from the last [days] days (including today) whose "Content" field
     * (Lesson.teachingContent — the same per-lesson text shown in the timetable/Stundenplan)
     * is filled in. This is what the "Unterrichtsinhalte" tab shows — it is a per-lesson
     * enrichment field from CalendarEntryDetail, NOT the Klassenbuch/classbook register (see
     * getClassbookEntries), which is a separate, unrelated WebUntis feature.
     *
     * Unlike getClassbookEntries (one cheap API call for the whole school year), populating
     * teachingContent needs one detail call PER lesson, so fetching a whole year up front isn't
     * viable. Instead this grows an internal cache incrementally: each call only fetches+enriches
     * the slice of days not already covered by a previous call, and merges it in — so repeatedly
     * widening the window (the "Weitere Tage laden" button) doesn't re-fetch days already loaded.
     *
     * anchorDate is always "today" (not the oldest day in the window) so that when a window is
     * wide enough to exceed the per-request enrichment cap, the days actually most relevant to
     * this tab — today and the most recent past days, e.g. "yesterday" — are prioritized and
     * always end up enriched, rather than being pushed out by the far end of the range.
     */
    suspend fun getTeachingContentEntries(days: Int, forceRefresh: Boolean = false): Result<List<Lesson>> {
        if (forceRefresh) {
            cacheTeachingContentDays = 0
            cacheTeachingContentEntries = emptyList()
        }
        val today = LocalDate.now()
        if (days > cacheTeachingContentDays) {
            val rangeStart = today.minusDays((days - 1).toLong())
            val rangeEnd = if (cacheTeachingContentDays > 0)
                today.minusDays(cacheTeachingContentDays.toLong())
            else
                today
            // Scale the enrichment cap with how many *new* days are being requested (roughly
            // 8 lessons/day) so a big first load or a big "Weitere Tage laden" jump doesn't get
            // silently truncated the way the fixed 40-lesson default (tuned for single-day
            // Stundenplan views) would.
            val newDays = days - cacheTeachingContentDays
            val cap = (newDays * 8).coerceIn(40, 400)
            val rangeResult = fetchLessonsInRange(
                rangeStart.toUntis(), rangeEnd.toUntis(), anchorDate = today, maxEnrich = cap
            )
            val newLessons = rangeResult.getOrElse {
                return if (cacheTeachingContentEntries.isNotEmpty()) {
                    // Already have something cached (e.g. from a smaller previous window) —
                    // prefer showing that over failing the whole tab outright.
                    Result.success(cacheTeachingContentEntries.filter {
                        val d = it.localDate ?: return@filter false
                        !d.isBefore(today.minusDays((cacheTeachingContentDays - 1).toLong()))
                    })
                } else Result.failure(it)
            }
            cacheTeachingContentEntries = (cacheTeachingContentEntries + newLessons).distinctBy { it.id }
            cacheTeachingContentDays = days
        }
        val cutoff = today.minusDays((days - 1).toLong())
        val filtered = cacheTeachingContentEntries.filter { lesson ->
            !lesson.teachingContent.isNullOrBlank() &&
                lesson.localDate?.let { !it.isBefore(cutoff) && !it.isAfter(today) } == true
        }
        return Result.success(filtered)
    }

    suspend fun getEvents(forceRefresh: Boolean = false, includePast: Boolean = false): Result<List<SchoolEvent>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { if (includePast) null else cacheEventsByAccount[currentAccountScope()] },
        store = { if (!includePast) cacheEventsByAccount[currentAccountScope()] = it },
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
                            isExam = true,
                            color = entry.color?.takeIf { it.isNotBlank() }
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

    /**
     * Single fetch backing both absence lists in the UI: the reported "Abwesenheits-Nachrichten"
     * ([Absence], unchanged shape/behaviour from before) and the per-lesson breakdown used for
     * the day-grouped "Liste der Abwesenheiten" ([AbsenceTime], with missedDays/missedHours/
     * missedMins). Both arrive in one classreg/absencetimes/student response and share one
     * cache entry, so [getAbsences] and [getAbsenceTimes] never cause two network round-trips
     * even when called back-to-back (e.g. AbsencesViewModel.load()) — the second call is served
     * from cache as long as the first one just refreshed it.
     */
    private suspend fun fetchAbsencesAndTimes(forceRefresh: Boolean = false): Result<Pair<List<Absence>, List<AbsenceTime>>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheAbsencesByAccount[currentAccountScope()] },
        store = { cacheAbsencesByAccount[currentAccountScope()] = it },
    ) {
        try {
            val token = getAuthHeader()
            val studentId = sessionManager.studentId
            if (studentId == 0) return@withCacheOrFetch Result.success(emptyList<Absence>() to emptyList())
            val today = LocalDate.now()
            val yearStart = if (today.monthValue >= 8) today.year else today.year - 1
            val startDate = "${yearStart}0801"
            val endDate   = "${yearStart + 1}1231"
            val resp = service().getAbsenceTimes(token, startDate, endDate, studentId)
            val raw = rawBody(resp) ?: return@withCacheOrFetch Result.success(emptyList<Absence>() to emptyList())
            val parsed: AbsenceTimesResponse = parseJson(raw)
            val absences = (parsed.data?.absences ?: emptyList()).sortedByDescending { it.startDate ?: 0 }
            val absenceTimes = parsed.data?.absenceTimes ?: emptyList()
            Result.success(absences to absenceTimes)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** The "Abwesenheits-Nachrichten" tab — one row per reported absence. */
    suspend fun getAbsences(forceRefresh: Boolean = false, excuseStatusId: Int = -1): Result<List<Absence>> =
        fetchAbsencesAndTimes(forceRefresh).map { it.first }

    /** The "Liste der Abwesenheiten" tab — one entry per missed lesson period. */
    suspend fun getAbsenceTimes(forceRefresh: Boolean = false): Result<List<AbsenceTime>> =
        fetchAbsencesAndTimes(forceRefresh).map { it.second }

    suspend fun getAbsencesMeta(forceRefresh: Boolean = false): Result<AbsencesMetaData> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheAbsencesMetaByAccount[currentAccountScope()] },
        store = { cacheAbsencesMetaByAccount[currentAccountScope()] = it },
    ) {
        try {
            val token = getAuthHeader()
            val resp = service().getAbsencesMeta(token)
            val raw = rawBody(resp) ?: return@withCacheOrFetch Result.failure(Exception("Keine Daten"))
            val metaResp: AbsencesMetaResponse = parseJson(raw)
            metaResp.data?.let { Result.success(it) } ?: Result.failure(Exception("Ungültige Daten"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Ensures a CSRF token is present before a write request, fetching one on demand if this
     *  session was established before this mechanism existed (or the initial fetch failed). */
    private suspend fun ensureCsrfToken() {
        if (sessionManager.csrfToken.isNullOrBlank()) fetchCsrfToken()
    }

    /**
     * Like rawBody(), but for the three absence write endpoints (create/update/delete), which
     * need an extra distinction rawBody() can't make on its own: a 403 with an *empty* body is
     * genuinely ambiguous there — it's just as often a missing Tenant-Id or CSRF token as an
     * actually-expired session. rawBody() alone always guesses "session expired" for that shape
     * (because that IS what an expired Bearer token looks like on other endpoints), which sends
     * the request through reAuthSilently() and a retry — but a missing Tenant-Id/CSRF-Token
     * isn't fixed by re-authenticating, so the retry 403s again with the exact same problem,
     * and the user is stuck seeing "Session abgelaufen" even right after a fresh login/app
     * start. Reads the body only once (unlike calling rawBody() after a separate peek, which
     * would consume okhttp's response body stream twice and silently return blank on the
     * second read).
     */
    private fun rawBodyForAbsenceWrite(
        r: retrofit2.Response<okhttp3.ResponseBody>, tenantId: String?, csrf: String?
    ): String? {
        if (r.headers()["X-WebUntis-Session-Expired"] == "true") throw SessionExpiredException()
        if (r.code() == 401) throw SessionExpiredException()

        val raw = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
        val bodyText = raw?.trim() ?: ""

        if (r.code() == 403 && bodyText.isBlank()) {
            val missing = buildList {
                if (tenantId.isNullOrBlank()) add("Tenant-Id")
                if (csrf.isNullOrBlank()) add("CSRF-Token")
            }
            if (missing.isNotEmpty()) {
                throw Exception(
                    "Anlegen/Ändern/Löschen fehlgeschlagen: ${missing.joinToString(" und ")} " +
                    "konnte(n) nicht ermittelt werden (HTTP 403). Das ist kein abgelaufenes " +
                    "Login — bitte einmal aus- und wieder einloggen, damit diese Werte neu " +
                    "geladen werden; tritt es danach weiterhin auf, ist es ein App-Bug und " +
                    "keine abgelaufene Sitzung."
                )
            }
            // Both present and it's STILL a bare 403 — that really does look like session expiry.
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
            throw Exception(if (extracted != null) "$extracted (HTTP ${r.code()})" else "HTTP ${r.code()}")
        }

        if (bodyText.startsWith("<html", ignoreCase = true) || bodyText.startsWith("<!DOCTYPE", ignoreCase = true)) {
            throw SessionExpiredException()
        }

        return bodyText.ifEmpty { null }
    }

    suspend fun createAbsence(req: CreateAbsenceRequest): Result<Absence> = withSessionRetry {
        try {
            ensureCsrfToken()
            val tenantId = tenantIdFromToken()
            val csrf = sessionManager.csrfToken
            // Diagnostic aid: if a write request still 403s despite a valid CSRF token, the next
            // thing to check is whether the active session is actually the parent/guardian
            // account (personType 12) rather than the student's own — this line makes that
            // visible without needing a debugger attached.
            android.util.Log.i(
                "WebUntis",
                "createAbsence: personType=${sessionManager.session?.personType} " +
                    "personId=${sessionManager.session?.personId} studentId=${req.studentId} " +
                    "tenantId=${tenantId ?: "MISSING"} csrfToken=${if (csrf.isNullOrBlank()) "MISSING" else "present"}"
            )
            // WebUntis write endpoints require Tenant-Id header (from JWT claim) + JSESSIONID
            // cookie + CSRF token. The Bearer token is intentionally omitted (scope mg:r =
            // read-only).
            val resp = service().createAbsence(null, tenantId, req)
            val raw = rawBodyForAbsenceWrite(resp, tenantId, csrf) ?: return@withSessionRetry Result.failure(Exception("Fehler beim Erstellen"))
            val json = JsonParser.parseString(raw).asJsonObject
            val resultObj = json.getAsJsonObject("data")?.getAsJsonObject("result")
            if (resultObj != null) Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            else Result.failure(Exception("Fehler: " + (json.getAsJsonObject("data")?.getAsJsonArray("conflicts")?.toString() ?: "Unbekannter Fehler")))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateAbsence(id: Int, req: CreateAbsenceRequest): Result<Absence> = withSessionRetry {
        try {
            ensureCsrfToken()
            val tenantId = tenantIdFromToken()
            val csrf = sessionManager.csrfToken
            val resp = service().updateAbsence(null, tenantId, id, req)
            val raw = rawBodyForAbsenceWrite(resp, tenantId, csrf) ?: return@withSessionRetry Result.failure(Exception("Fehler beim Aktualisieren"))
            val json = JsonParser.parseString(raw).asJsonObject
            val resultObj = json.getAsJsonObject("data")?.getAsJsonObject("result")
            if (resultObj != null) Result.success(parseJson(resultObj.toString(), object : TypeToken<Absence>() {}))
            else Result.failure(Exception("Fehler beim Aktualisieren"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteAbsence(id: Int): Result<Unit> = withSessionRetry {
        try {
            ensureCsrfToken()
            val tenantId = tenantIdFromToken()
            val csrf = sessionManager.csrfToken
            val resp = service().deleteAbsence(null, tenantId, DeleteAbsenceRequest(listOf(id)))
            rawBodyForAbsenceWrite(resp, tenantId, csrf)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

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

    /**
     * Logs in as one additional (child) account through its own isolated session (see
     * RetrofitFactory.createIsolated — this MUST be isolated, not the shared client: the next
     * step, getBearerToken(), takes no credential of its own and mints a token for whichever
     * session cookie comes along, so sharing the primary's cookie jar here would silently mint
     * a token for the PRIMARY account instead of this one) and returns its bearer token.
     * Updates that account's cached label/name/type along the way. Returns null on any failure
     * (wrong/expired credentials, network error, ...) rather than throwing, since a single
     * failed child shouldn't stop the others (or the primary) from loading.
     *
     * The returned token is plain and Bearer-authenticated, so — unlike the login/token-minting
     * step itself — callers can use it with the ordinary shared client for the actual data
     * fetch; only this login step needs the isolated cookie jar.
     */
    private suspend fun loginAdditionalAccount(
        server: String, schoolname: String, account: SessionManager.SecondAccount
    ): String? {
        return try {
            val svc = retrofitFactory.createIsolated(server)
            val body = JsonRpcRequest(
                method = "authenticate",
                params = mapOf<String, Any>("user" to account.username, "password" to account.password, "client" to "android")
            )
            val loginResp = svc.jsonRpcLogin(schoolname, body)
            val loginRaw = loginResp.body()?.string()?.trim() ?: return null
            val rpcResp: JsonRpcResponse<AuthResult> =
                parseJson(loginRaw, object : TypeToken<JsonRpcResponse<AuthResult>>() {})
            val authResult = rpcResp.result ?: return null

            val resolvedLabel = account.label.ifBlank {
                authResult.personName?.takeIf { it.isNotBlank() } ?: account.username
            }
            sessionManager.addOrUpdateAdditionalAccount(
                account.copy(
                    personType = authResult.personType ?: 0,
                    personName = authResult.personName ?: "",
                    label = resolvedLabel
                )
            )

            val bearerResp = svc.getBearerToken()
            val bearerRaw = bearerResp.body()?.string()?.trim() ?: return null
            val token = if (bearerRaw.startsWith("{"))
                com.google.gson.JsonParser.parseString(bearerRaw).asJsonObject.get("token")?.asString ?: bearerRaw
            else bearerRaw.trim('"')
            additionalBearerTokens[account.key] = token
            token
        } catch (e: Exception) {
            android.util.Log.e("WebUntis", "Zusatz-Account (${account.label.ifBlank { account.username }}) – Login fehlgeschlagen", e)
            null
        }
    }

    private suspend fun fetchMessagesForAdditionalAccount(
        server: String, schoolname: String, account: SessionManager.SecondAccount
    ): List<Message> {
        val token = loginAdditionalAccount(server, schoolname, account) ?: return emptyList()
        return try {
            // The label may have just been resolved/updated inside loginAdditionalAccount —
            // re-read it so messages carry the current one, not the possibly-blank one passed in.
            val label = sessionManager.additionalAccounts.firstOrNull { it.key == account.key }?.label
                ?: account.label.ifBlank { account.username }
            val msgResp = service().getMessagesAuth("Bearer $token")
            val raw = rawBody(msgResp) ?: return emptyList()
            val msgsResp: MessagesResponse = parseJson(raw)
            ((msgsResp.incomingMessages ?: emptyList()) +
                    (msgsResp.readConfirmationMessages ?: emptyList()))
                .map { it.copy(accountLabel = label) }
        } catch (e: Exception) {
            android.util.Log.e("WebUntis", "Zusatz-Account – Nachrichten konnten nicht geladen werden", e)
            emptyList()
        }
    }

    /** Gibt den Bearer-Token (inkl. "Bearer "-Prefix) zurück, der zu dieser Nachricht gehört. */
    private fun tokenForMessage(msg: Message): String? {
        val raw = bearerToken ?: return null
        val primaryHeader = "Bearer $raw"
        val label = msg.accountLabel
        if (label.isNullOrBlank()) return primaryHeader
        val account = sessionManager.additionalAccounts.firstOrNull { it.label == label } ?: return primaryHeader
        return additionalBearerTokens[account.key]?.let { "Bearer $it" } ?: primaryHeader
    }

    suspend fun getMessages(forceRefresh: Boolean = false): Result<List<Message>> = withCacheOrFetch(
        forceRefresh = forceRefresh,
        cache = { cacheMessages },
        store = { cacheMessages = it },
    ) {
        try {
            val session      = sessionManager.session
            val primaryLabel = sessionManager.mainAccountLabel

            val token   = getAuthHeader() ?: return@withCacheOrFetch Result.failure(Exception("Nicht authentifiziert"))
            val primary = fetchMessagesWithToken(token, primaryLabel)
            val server  = session?.server
            val additional = if (server == null) emptyList() else {
                // Sequential, not parallel: each of these is a full separate login, and running
                // them concurrently would mean juggling N isolated cookie jars' login/token
                // steps at once for marginal speed gain on what's already a background refresh.
                sessionManager.additionalAccounts.flatMap { acc ->
                    fetchMessagesForAdditionalAccount(server, session.schoolname, acc)
                }
            }

            // Deduplizierung: IDs sind nur innerhalb eines Accounts eindeutig.
            // accountLabel + id als zusammengesetzter Key verhindert, dass Nachrichten
            // eines Zusatz-Accounts fälschlicherweise herausgefiltert werden.
            val seenKeys = mutableSetOf<String>()
            val merged   = (primary + additional)
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
            val token = tokenForMessage(msg)
                ?: return@withSessionRetry Result.failure(Exception("Nicht authentifiziert"))

            val urlResp = service().getAttachmentStorageUrl(attachmentId, token)
            val urlRaw = rawBody(urlResp)
                ?: return@withSessionRetry Result.failure(Exception("Keine Download-URL"))

            val storageUrl: AttachmentStorageUrl = try {
                parseJson(urlRaw)
            } catch (e: Exception) {
                android.util.Log.e("WebUntis", "Attachment storage-url response could not be parsed", e)
                return@withSessionRetry Result.failure(Exception("Antwort konnte nicht gelesen werden: ${e.message}"))
            }

            val downloadUrl = storageUrl.downloadUrl
                ?: return@withSessionRetry Result.failure(Exception("Download-URL fehlt"))
            val headers = storageUrl.additionalHeaders ?: emptyList()
            val encAlg = headers.firstOrNull { it.key == "x-amz-server-side-encryption-customer-algorithm" }?.value ?: ""
            val encKey = headers.firstOrNull { it.key == "x-amz-server-side-encryption-customer-key" }?.value ?: ""
            val encMd5 = headers.firstOrNull { it.key == "x-amz-server-side-encryption-customer-key-md5" }?.value ?: ""

            val dlResp = service().downloadFromStorage(downloadUrl, encAlg, encKey, encMd5)
            if (!dlResp.isSuccessful) {
                android.util.Log.w("WebUntis", "Attachment storage download failed — HTTP ${dlResp.code()}")
                return@withSessionRetry Result.failure(Exception("Storage-Download fehlgeschlagen (HTTP ${dlResp.code()})"))
            }
            val body = dlResp.body() ?: return@withSessionRetry Result.failure(Exception("Keine Daten"))
            // The storage backend (S3) returns the ORIGINAL upload's Content-Type here — this is
            // far more reliable than guessing from the attachment's display name, which often has
            // no file extension at all (e.g. images named just by an internal id).
            val declaredMimeType = body.contentType()?.toString()
            val expectedLength = body.contentLength()
            // Reading the (streaming) body performs real blocking network I/O — never do this on
            // whatever thread called us (viewModelScope.launch defaults to the Main dispatcher),
            // or it throws NetworkOnMainThreadException. Must run on Dispatchers.IO explicitly.
            val bytes = withContext(Dispatchers.IO) { readBytesWithProgress(body, onProgress) }
            // Never silently "succeed" with an empty file — if the server told us how many bytes
            // to expect and we got fewer (or none), surface that as a real error instead of
            // letting the caller save/open a corrupt 0-byte file without any explanation.
            if (bytes.isEmpty() || (expectedLength > 0 && bytes.size.toLong() < expectedLength)) {
                return@withSessionRetry Result.failure(
                    Exception("Download unvollständig (${bytes.size} von ${if (expectedLength > 0) expectedLength else "?"} Bytes)")
                )
            }
            Result.success(bytes to declaredMimeType)
        } catch (e: SessionExpiredException) {
            throw e
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception: a LinkageError/NoSuchMethodError from a
            // release-build (R8) reflection mismatch would otherwise silently skip all the
            // logging above without ever reaching a catch block, since those are Errors, not
            // Exceptions. Logging here guarantees SOME "WebUntis" line no matter what breaks.
            android.util.Log.e("WebUntis", "downloadAttachment: UNEXPECTED ${t.javaClass.name}: ${t.message}", t)
            Result.failure(Exception("${t.javaClass.simpleName}: ${t.message}"))
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
            val primaryLabel = sessionManager.mainAccountLabel
            val token        = getAuthHeader() ?: return@withCacheOrFetch Result.failure(Exception("Nicht authentifiziert"))
            val primary      = fetchFolderMessages(token, "SENT", primaryLabel)
            val server       = session?.server
            val additional   = if (server == null) emptyList() else sessionManager.additionalAccounts.flatMap { acc ->
                fetchFolderForAdditionalAccount(server, session.schoolname, acc, "SENT")
            }
            Result.success((primary + additional).sortedByDescending { it.sentDateTimeForSorting })
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
            val primaryLabel = sessionManager.mainAccountLabel
            val token        = getAuthHeader() ?: return@withCacheOrFetch Result.failure(Exception("Nicht authentifiziert"))
            val primary      = fetchFolderMessages(token, "DRAFTS", primaryLabel)
            val server       = session?.server
            val additional   = if (server == null) emptyList() else sessionManager.additionalAccounts.flatMap { acc ->
                fetchFolderForAdditionalAccount(server, session.schoolname, acc, "DRAFTS")
            }
            Result.success((primary + additional).sortedByDescending { it.sentDateTimeForSorting })
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

    private suspend fun fetchFolderForAdditionalAccount(
        server: String, schoolname: String, account: SessionManager.SecondAccount, folder: String
    ): List<Message> {
        val token = loginAdditionalAccount(server, schoolname, account) ?: return emptyList()
        val label = sessionManager.additionalAccounts.firstOrNull { it.key == account.key }?.label
            ?: account.label.ifBlank { account.username }
        return try {
            fetchFolderMessages("Bearer $token", folder, label)
        } catch (e: Exception) {
            android.util.Log.e("WebUntis", "Zusatz-Account – $folder fehlgeschlagen", e)
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
        fromAccountKey: String? = null
    ): Result<Unit> {
        return try {
            ensureCsrfToken() // same session cookie rides along even on this Bearer-token endpoint
            val token: String = if (fromAccountKey != null) {
                val acc     = sessionManager.additionalAccounts.firstOrNull { it.key == fromAccountKey }
                    ?: return Result.failure(Exception("Zusatz-Account nicht gefunden"))
                val session = sessionManager.session ?: return Result.failure(Exception("Nicht eingeloggt"))
                loginAdditionalAccount(session.server, session.schoolname, acc)
                    ?.let { "Bearer $it" }
                    ?: return Result.failure(Exception("Anmeldung für Zusatz-Account fehlgeschlagen"))
            } else {
                getAuthHeader() ?: return Result.failure(Exception("Nicht authentifiziert"))
            }

            val schoolYearId = getCurrentSchoolYear().getOrNull()?.id ?: 0

            // Field names/shape here are NOT guessed — they match exactly what the official web
            // client sends to this same endpoint (verified via a real, successful send).
            // NOTE: no reply-threading field is included — the confirmed-working payload doesn't
            // have one, so [replyToMsgId] is currently unused here. If replies turn out to not
            // thread correctly, that's the next thing to investigate (likely a different field
            // name in v2, or replies might just be plain new messages server-side).
            val gson = com.google.gson.Gson()
            val payload = buildString {
                append("{")
                append("\"subject\":${gson.toJson(subject)},")
                append("\"content\":${gson.toJson(content)},")
                append("\"requestConfirmation\":false,")
                append("\"recipientUserIds\":${gson.toJson(recipientPersonIds)},")
                append("\"oneDriveAttachments\":[],")
                append("\"forbidReply\":${!allowReply}")
                append("}")
            }
            val requestPart = okhttp3.MultipartBody.Part.createFormData(
                "request", "blob",
                payload.toRequestBody("application/json".toMediaTypeOrNull())
            )

            val resp = service().sendMessageV2(token, schoolYearId, requestPart)
            // The old v1 endpoint never checked this at all — that's exactly how "message sent"
            // could show up even though nothing was actually created server-side.
            rawBody(resp) ?: return Result.failure(Exception("Nachricht wurde nicht gesendet"))

            // Invalidate inbox + sent caches
            cacheMessages = null; cacheSentMessages = null
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is SessionExpiredException) throw e
            Result.failure(e)
        }
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
        fromAccountKey: String? = null,
        attachments: List<Pair<String, ByteArray>> = emptyList(),   // new files: filename → bytes
        removedAttachmentIds: List<String> = emptyList()            // existing storage IDs to delete
    ): Result<Message> {
        return try {
            ensureCsrfToken() // same session cookie rides along even on this Bearer-token endpoint
            val token: String = if (fromAccountKey != null) {
                val acc     = sessionManager.additionalAccounts.firstOrNull { it.key == fromAccountKey }
                    ?: return Result.failure(Exception("Zusatz-Account nicht gefunden"))
                val session = sessionManager.session ?: return Result.failure(Exception("Nicht eingeloggt"))
                loginAdditionalAccount(session.server, session.schoolname, acc)
                    ?.let { "Bearer $it" }
                    ?: return Result.failure(Exception("Anmeldung für Zusatz-Account fehlgeschlagen"))
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
            ensureCsrfToken() // same session cookie rides along even on this Bearer-token endpoint
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
    /**
     * Verifies a new additional (child) account's credentials and adds it to the list (or
     * updates it in place if that username is already configured). Verification works by
     * briefly logging in AS that account through the primary session/login flow (this is a
     * one-off verification step, not how the account is used afterward — ongoing data fetches
     * use loginAdditionalAccount()'s isolated session instead, see RetrofitFactory.createIsolated),
     * then restoring the primary session from its stored credentials.
     */
    suspend fun verifyAndAddAdditionalAccount(username: String, password: String, label: String): Result<String> {
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
                    val account = SessionManager.SecondAccount(
                        username   = username,
                        password   = password,
                        label      = label.trim(),
                        personType = sessionData.personType,
                        personName = sessionData.personName
                    )
                    sessionManager.addOrUpdateAdditionalAccount(account)
                    // Restore primary session so the main account stays logged in
                    login(session.server, session.schoolname,
                        sessionManager.storedCredentials?.first ?: session.username,
                        sessionManager.storedCredentials?.second ?: "")
                    val info = buildString {
                        if (account.personName.isNotBlank()) append(account.personName)
                        if (account.accountTypeLabel.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(account.accountTypeLabel)
                        }
                        if (account.label.isNotBlank()) {
                            if (isNotEmpty()) append(" (")
                            append(account.label)
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
