package com.webuntis.dashboard.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.webuntis.dashboard.model.SessionData
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // ── Two SharedPreferences files ───────────────────────────────────────────
    //
    // prefs      ("webuntis_session")  — credentials, session token, second account
    //                                    encrypted if possible, plain fallback
    // plainPrefs ("webuntis_settings") — UI settings (timetable days, toggles, cache TTL)
    //                                    never needs encryption
    //
    // RULE: never call plainPrefs inside createSecurePrefs() — lazy init order is undefined.
    // RULE: clearSession() must NEVER delete storedCredentials.

    private val plainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("webuntis_settings", Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences by lazy { createSecurePrefs() }

    private fun createSecurePrefs(): SharedPreferences {
        // Try 1 — normal encrypted prefs
        runCatching {
            val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, "webuntis_session", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure {
            android.util.Log.w("SessionManager", "EncryptedSharedPreferences attempt 1 failed", it)
        }

        // Try 2 — delete stale keystore alias and retry
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (ks.containsAlias("_androidx_security_master_key"))
                ks.deleteEntry("_androidx_security_master_key")
            val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, "webuntis_session", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure {
            android.util.Log.w("SessionManager", "EncryptedSharedPreferences attempt 2 failed", it)
        }

        // Try 3 — delete corrupted prefs file and retry (fresh key)
        runCatching {
            java.io.File(context.filesDir.parent, "shared_prefs/webuntis_session.xml")
                .takeIf { it.exists() }?.delete()
            val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, "webuntis_session", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure {
            android.util.Log.e("SessionManager", "EncryptedSharedPreferences attempt 3 failed — falling back to plain prefs", it)
        }

        // Final fallback — plain unencrypted prefs (app sandbox still protects at rest)
        // IMPORTANT: use a separate file name so we never accidentally mix encrypted
        // and plain instances of the same file name.
        return context.getSharedPreferences("webuntis_session_plain", Context.MODE_PRIVATE)
    }

    // ── Session ───────────────────────────────────────────────────────────────

    var session: SessionData?
        get() {
            val server = prefs.getString(KEY_SERVER, null) ?: return null
            return SessionData(
                server     = server,
                schoolname = prefs.getString(KEY_SCHOOLNAME, "") ?: "",
                username   = prefs.getString(KEY_USERNAME,   "") ?: "",
                sessionId  = prefs.getString(KEY_SESSION_ID, "") ?: "",
                personId   = prefs.getInt(KEY_PERSON_ID, 0),
                classId    = prefs.getInt(KEY_CLASS_ID,  0),
                personName = prefs.getString(KEY_PERSON_NAME, "") ?: "",
                personType = prefs.getInt(KEY_PERSON_TYPE, 0)
            )
        }
        set(value) {
            if (value == null) {
                // Only clear session token fields — NEVER touch credentials here
                prefs.edit()
                    .remove(KEY_SERVER).remove(KEY_SCHOOLNAME).remove(KEY_USERNAME)
                    .remove(KEY_SESSION_ID).remove(KEY_PERSON_ID).remove(KEY_CLASS_ID)
                    .remove(KEY_PERSON_NAME).remove(KEY_PERSON_TYPE).remove(KEY_SESSION_TIME)
                    .apply()
            } else {
                prefs.edit()
                    .putString(KEY_SERVER,      value.server)
                    .putString(KEY_SCHOOLNAME,  value.schoolname)
                    .putString(KEY_USERNAME,    value.username)
                    .putString(KEY_SESSION_ID,  value.sessionId)
                    .putInt(KEY_PERSON_ID,      value.personId)
                    .putInt(KEY_CLASS_ID,       value.classId)
                    .putString(KEY_PERSON_NAME, value.personName)
                    .putInt(KEY_PERSON_TYPE,    value.personType)
                    .putLong(KEY_SESSION_TIME,  System.currentTimeMillis())
                    .apply()
            }
        }

    fun isSessionFresh(): Boolean {
        val ts = prefs.getLong(KEY_SESSION_TIME, 0L)
        return ts != 0L && (System.currentTimeMillis() - ts) < SESSION_TTL_MS
    }

    fun getSessionTime(): Long = prefs.getLong(KEY_SESSION_TIME, 0L)

    fun touchSession() {
        prefs.edit().putLong(KEY_SESSION_TIME, System.currentTimeMillis()).apply()
    }

    /** Clears session token only. Credentials and second account are preserved. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_SERVER).remove(KEY_SCHOOLNAME).remove(KEY_USERNAME)
            .remove(KEY_SESSION_ID).remove(KEY_PERSON_ID).remove(KEY_CLASS_ID)
            .remove(KEY_PERSON_NAME).remove(KEY_PERSON_TYPE).remove(KEY_SESSION_TIME)
            .apply()
        // studentId is session-derived — clear it too so it gets re-resolved after next login
        prefs.edit().remove(KEY_STUDENT_ID).apply()
    }

    /** Clears everything including credentials. Use only on explicit logout. */
    fun clearAll() {
        prefs.edit().clear().apply()
        plainPrefs.edit().clear().apply()
    }

    // ── Stored credentials (persist across sessions) ──────────────────────────

    var storedCredentials: Pair<String, String>?
        get() {
            val u = prefs.getString(KEY_STORED_USER, null) ?: return null
            val p = prefs.getString(KEY_STORED_PASS, null) ?: return null
            return Pair(u, p)
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_STORED_USER).remove(KEY_STORED_PASS).apply()
            } else {
                prefs.edit()
                    .putString(KEY_STORED_USER, value.first)
                    .putString(KEY_STORED_PASS, value.second)
                    .apply()
            }
        }

    /** Server + schoolname from last successful login — available even when session token expired. */
    val storedSessionMeta: Pair<String, String>?
        get() {
            val server = prefs.getString(KEY_SERVER, null) ?: return null
            val school = prefs.getString(KEY_SCHOOLNAME, null) ?: return null
            return Pair(server, school)
        }

    // ── Student ID (for parent accounts) ─────────────────────────────────────

    var studentId: Int
        get() = prefs.getInt(KEY_STUDENT_ID, 0)
        set(v) = prefs.edit().putInt(KEY_STUDENT_ID, v).apply()

    // ── Class ID ─────────────────────────────────────

    var classId: Int
        get() = prefs.getInt(KEY_CLASS_ID, 0)
        set(v) = prefs.edit().putInt(KEY_CLASS_ID, v).apply()

    // ── Second account ────────────────────────────────────────────────────────

    data class SecondAccount(
        val username:   String,
        val password:   String,
        val label:      String,
        val personType: Int    = 0,
        val personName: String = ""
    ) {
        val accountTypeLabel: String get() = when (personType) {
            2    -> "Lehrer"
            5    -> "Schüler"
            12   -> "Eltern"
            else -> "Unbekannt"
        }
    }

    var secondAccount: SecondAccount?
        get() {
            val u = prefs.getString(KEY_SECOND_USER, null) ?: return null
            val p = prefs.getString(KEY_SECOND_PASS, null) ?: return null
            return SecondAccount(
                username   = u,
                password   = p,
                label      = prefs.getString(KEY_SECOND_LABEL, "") ?: "",
                personType = prefs.getInt(KEY_SECOND_TYPE, 0),
                personName = prefs.getString(KEY_SECOND_NAME, "") ?: ""
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit()
                    .remove(KEY_SECOND_USER).remove(KEY_SECOND_PASS)
                    .remove(KEY_SECOND_LABEL).remove(KEY_SECOND_TYPE).remove(KEY_SECOND_NAME)
                    .apply()
            } else {
                prefs.edit()
                    .putString(KEY_SECOND_USER,  value.username)
                    .putString(KEY_SECOND_PASS,  value.password)
                    .putString(KEY_SECOND_LABEL, value.label)
                    .putInt(KEY_SECOND_TYPE,     value.personType)
                    .putString(KEY_SECOND_NAME,  value.personName)
                    .apply()
            }
        }

    // ── UI settings (plainPrefs) ──────────────────────────────────────────────

    var timetableDays: Int
        get() = plainPrefs.getInt(KEY_TIMETABLE_DAYS, DEFAULT_TIMETABLE_DAYS)
        set(value) { plainPrefs.edit().putInt(KEY_TIMETABLE_DAYS, value.coerceIn(MIN_TIMETABLE_DAYS, MAX_TIMETABLE_DAYS)).apply() }

    var showLongSubjects: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_LONG_SUBJECTS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_LONG_SUBJECTS, value).apply() }

    var showLongTeachers: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_LONG_TEACHERS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_LONG_TEACHERS, value).apply() }

    var showLongRooms: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_LONG_ROOMS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_LONG_ROOMS, value).apply() }

    // Only relevant when the matching showLong* above is enabled — appends the abbreviation
    // in parentheses after the spelled-out name, e.g. "Mathematik (M)".
    var showShortSubjectInParens: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_SHORT_SUBJECT_PARENS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_SHORT_SUBJECT_PARENS, value).apply() }

    var showShortTeacherInParens: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_SHORT_TEACHER_PARENS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_SHORT_TEACHER_PARENS, value).apply() }

    var showShortRoomInParens: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_SHORT_ROOM_PARENS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_SHORT_ROOM_PARENS, value).apply() }

    var useCompactWeekView: Boolean
        get() = plainPrefs.getBoolean(KEY_USE_COMPACT_WEEK_VIEW, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_USE_COMPACT_WEEK_VIEW, value).apply() }

    /** Whether the timetable shows the logged-in person's own schedule, a whole class's
     *  schedule, or the personal schedule with selected class-plan subjects filled into gaps. */
    enum class TimetableViewMode { PERSONAL, CLASS, COMBINED }

    var timetableViewMode: TimetableViewMode
        get() = when (plainPrefs.getString(KEY_TIMETABLE_VIEW_MODE, null)) {
            TimetableViewMode.CLASS.name    -> TimetableViewMode.CLASS
            TimetableViewMode.COMBINED.name -> TimetableViewMode.COMBINED
            else -> TimetableViewMode.PERSONAL
        }
        set(value) { plainPrefs.edit().putString(KEY_TIMETABLE_VIEW_MODE, value.name).apply() }

    /** True if a class element id is known (i.e. a "Klassenstundenplan" can actually be requested). */
    val canShowClassTimetable: Boolean get() = (session?.classId ?: 0) > 0

    // ── Combined view: which class-plan subjects may fill gaps in the personal plan ────────────

    private val overlayGson = com.google.gson.Gson()

    /** Subject names (as shown in the class plan) that are allowed to fill empty slots in COMBINED mode. */
    var combinedOverlaySubjects: Set<String>
        get() {
            val raw = plainPrefs.getString(KEY_COMBINED_OVERLAY_SUBJECTS, null) ?: return emptySet()
            return runCatching {
                overlayGson.fromJson(raw, Array<String>::class.java)?.toSet() ?: emptySet()
            }.getOrDefault(emptySet())
        }
        set(value) {
            plainPrefs.edit().putString(KEY_COMBINED_OVERLAY_SUBJECTS, overlayGson.toJson(value.toTypedArray())).apply()
        }

    var cacheTtlMinutes: Int
        get() = plainPrefs.getInt(KEY_CACHE_TTL, DEFAULT_CACHE_TTL)
        set(value) { plainPrefs.edit().putInt(KEY_CACHE_TTL, value.coerceIn(MIN_CACHE_TTL, MAX_CACHE_TTL)).apply() }

    fun isCacheFresh(lastFetchMs: Long): Boolean {
        val ttl = cacheTtlMinutes
        if (ttl == 0 || lastFetchMs == 0L) return false
        return (System.currentTimeMillis() - lastFetchMs) < ttl * 60_000L
    }

    // ── Export / Import ───────────────────────────────────────────────────────

    fun exportSettings(): String {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val obj = com.google.gson.JsonObject().apply {
            addProperty("version", 1)
            session?.let {
                addProperty("server",     it.server)
                addProperty("schoolname", it.schoolname)
                addProperty("username",   it.username)
                addProperty("personName", it.personName)
                addProperty("personType", it.personType)
            }
            storedCredentials?.let { addProperty("password", it.second) }
            secondAccount?.let { acc ->
                add("secondAccount", com.google.gson.JsonObject().apply {
                    addProperty("username",   acc.username)
                    addProperty("password",   acc.password)
                    addProperty("label",      acc.label)
                    addProperty("personType", acc.personType)
                    addProperty("personName", acc.personName)
                })
            }
            addProperty("timetableDays",      timetableDays)
            addProperty("showLongSubjects",   showLongSubjects)
            addProperty("showLongTeachers",   showLongTeachers)
            addProperty("showLongRooms",      showLongRooms)
            addProperty("showShortSubjectInParens", showShortSubjectInParens)
            addProperty("showShortTeacherInParens", showShortTeacherInParens)
            addProperty("showShortRoomInParens",    showShortRoomInParens)
            addProperty("useCompactWeekView", useCompactWeekView)
            addProperty("cacheTtlMinutes",    cacheTtlMinutes)
            addProperty("timetableViewMode",  timetableViewMode.name)
            add("combinedOverlaySubjects", com.google.gson.JsonArray().apply {
                combinedOverlaySubjects.forEach { add(it) }
            })
        }
        return gson.toJson(obj)
    }

    fun importSettings(json: String): ImportResult {
        return try {
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            var primaryUpdated = false
            var secondUpdated  = false

            val server     = obj.get("server")?.asString
            val schoolname = obj.get("schoolname")?.asString
            val username   = obj.get("username")?.asString
            val password   = obj.get("password")?.asString
            val personName = obj.get("personName")?.asString ?: ""
            val personType = obj.get("personType")?.asInt ?: 0

            if (!server.isNullOrBlank() && !schoolname.isNullOrBlank() &&
                !username.isNullOrBlank() && !password.isNullOrBlank()) {
                storedCredentials = Pair(username, password)
                session = session?.copy(
                    server = server, schoolname = schoolname,
                    username = username, personName = personName, personType = personType
                ) ?: SessionData(
                    server = server, schoolname = schoolname, username = username,
                    sessionId = "", personId = 0, classId = 0,
                    personName = personName, personType = personType
                )
                primaryUpdated = true
            }

            obj.getAsJsonObject("secondAccount")?.let { sa ->
                val u = sa.get("username")?.asString
                val p = sa.get("password")?.asString
                if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
                    secondAccount = SecondAccount(
                        username   = u,
                        password   = p,
                        label      = sa.get("label")?.asString ?: "",
                        personType = sa.get("personType")?.asInt ?: 0,
                        personName = sa.get("personName")?.asString ?: ""
                    )
                    secondUpdated = true
                }
            }

            obj.get("timetableDays")?.asInt?.let      { timetableDays      = it }
            obj.get("showLongSubjects")?.asBoolean?.let { showLongSubjects  = it }
            obj.get("showLongTeachers")?.asBoolean?.let { showLongTeachers  = it }
            obj.get("showLongRooms")?.asBoolean?.let    { showLongRooms     = it }
            obj.get("showShortSubjectInParens")?.asBoolean?.let { showShortSubjectInParens = it }
            obj.get("showShortTeacherInParens")?.asBoolean?.let { showShortTeacherInParens = it }
            obj.get("showShortRoomInParens")?.asBoolean?.let    { showShortRoomInParens    = it }
            obj.get("useCompactWeekView")?.asBoolean?.let { useCompactWeekView = it }
            obj.get("cacheTtlMinutes")?.asInt?.let    { cacheTtlMinutes    = it }
            obj.get("timetableViewMode")?.asString?.let { raw ->
                runCatching { TimetableViewMode.valueOf(raw) }.getOrNull()?.let { timetableViewMode = it }
            }
            obj.getAsJsonArray("combinedOverlaySubjects")?.let { arr ->
                combinedOverlaySubjects = arr.mapNotNull { it.asString }.toSet()
            }

            ImportResult.Success(primaryUpdated = primaryUpdated, secondUpdated = secondUpdated)
        } catch (e: Exception) {
            ImportResult.Error("Import fehlgeschlagen: ${e.message}")
        }
    }

    sealed class ImportResult {
        data class Success(val primaryUpdated: Boolean, val secondUpdated: Boolean) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    companion object {
        private const val KEY_SERVER       = "server"
        private const val KEY_SCHOOLNAME   = "schoolname"
        private const val KEY_USERNAME     = "username"
        private const val KEY_SESSION_ID   = "session_id"
        private const val KEY_PERSON_ID    = "person_id"
        private const val KEY_CLASS_ID     = "class_id"
        private const val KEY_PERSON_NAME  = "person_name"
        private const val KEY_PERSON_TYPE  = "person_type"
        private const val KEY_SESSION_TIME = "session_time"
        private const val KEY_STORED_USER  = "stored_user"
        private const val KEY_STORED_PASS  = "stored_pass"
        private const val KEY_STUDENT_ID   = "student_id"
        private const val KEY_SECOND_USER  = "second_user"
        private const val KEY_SECOND_PASS  = "second_pass"
        private const val KEY_SECOND_LABEL = "second_label"
        private const val KEY_SECOND_TYPE  = "second_type"
        private const val KEY_SECOND_NAME  = "second_name"
        private const val KEY_TIMETABLE_DAYS        = "timetable_days"
        private const val KEY_SHOW_LONG_SUBJECTS     = "show_long_subjects"
        private const val KEY_SHOW_LONG_TEACHERS     = "show_long_teachers"
        private const val KEY_SHOW_LONG_ROOMS        = "show_long_rooms"
        private const val KEY_SHOW_SHORT_SUBJECT_PARENS = "show_short_subject_parens"
        private const val KEY_SHOW_SHORT_TEACHER_PARENS = "show_short_teacher_parens"
        private const val KEY_SHOW_SHORT_ROOM_PARENS    = "show_short_room_parens"
        private const val KEY_USE_COMPACT_WEEK_VIEW  = "use_compact_week_view"
        private const val KEY_TIMETABLE_VIEW_MODE    = "timetable_view_mode"
        private const val KEY_COMBINED_OVERLAY_SUBJECTS = "combined_overlay_subjects"
        private const val KEY_CACHE_TTL              = "cache_ttl_minutes"
        const val DEFAULT_TIMETABLE_DAYS = 5
        const val MIN_TIMETABLE_DAYS     = 1
        const val MAX_TIMETABLE_DAYS     = 20
        const val DEFAULT_CACHE_TTL      = 5
        const val MIN_CACHE_TTL          = 0
        const val MAX_CACHE_TTL          = 60
        private const val SESSION_TTL_MS = 45 * 60 * 1000L
    }
}
