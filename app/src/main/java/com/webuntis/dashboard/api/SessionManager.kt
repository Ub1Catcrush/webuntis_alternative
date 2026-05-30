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
    /**
     * Session prefs — encrypted when the Keystore is available, plain fallback otherwise.
     * On custom ROMs / API 29 devices the Android Keystore can be unavailable or broken.
     * We fall back to plainPrefs so the app stays usable; the session token is the only
     * secret stored here (credentials moved to plainPrefs already).
     */
    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefsOrFallback()
    }

    private fun createEncryptedPrefsOrFallback(): SharedPreferences {
        // Attempt 1: normal EncryptedSharedPreferences
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, "webuntis_session", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e1: Exception) {
            android.util.Log.w("SessionManager",
                "EncryptedSharedPreferences failed (attempt 1), retrying after clearing keystore entry", e1)
        }

        // Attempt 2: delete stale keystore entry and retry (helps on ROM upgrades / re-installs)
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias("_androidx_security_master_key")) {
                ks.deleteEntry("_androidx_security_master_key")
                android.util.Log.w("SessionManager", "Deleted stale master key, retrying…")
            }
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, "webuntis_session", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e2: Exception) {
            android.util.Log.w("SessionManager",
                "EncryptedSharedPreferences failed (attempt 2), falling back to plainPrefs", e2)
        }

        // Attempt 3: also delete the corrupted encrypted file and retry fresh
        try {
            val encFile = context.getSharedPrefsFile("webuntis_session")
            if (encFile.exists()) {
                encFile.delete()
                android.util.Log.w("SessionManager", "Deleted corrupted prefs file, retrying…")
            }
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, "webuntis_session", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e3: Exception) {
            android.util.Log.e("SessionManager",
                "EncryptedSharedPreferences unavailable on this device — using plainPrefs for session storage", e3)
        }

        // Final fallback: plainPrefs (unencrypted). Session token is short-lived anyway.
        return plainPrefs
    }

    /** Extension to locate the SharedPreferences backing file (used for cleanup). */
    private fun Context.getSharedPrefsFile(name: String): java.io.File {
        // Standard Android path: /data/data/<pkg>/shared_prefs/<name>.xml
        return java.io.File(filesDir.parent, "shared_prefs/$name.xml")
    }

    var session: SessionData?
        get() {
            val server = prefs.getString(KEY_SERVER, null) ?: return null
            return SessionData(
                server = server,
                schoolname = prefs.getString(KEY_SCHOOLNAME, "") ?: "",
                username = prefs.getString(KEY_USERNAME, "") ?: "",
                sessionId = prefs.getString(KEY_SESSION_ID, "") ?: "",
                personId = prefs.getInt(KEY_PERSON_ID, 0),
                classId = prefs.getInt(KEY_CLASS_ID, 0),
                personName = prefs.getString(KEY_PERSON_NAME, "") ?: "",
                personType = prefs.getInt(KEY_PERSON_TYPE, 0)
            )
        }
        set(value) {
            if (value == null) {
                clearSession()
            } else {
                prefs.edit()
                    .putString(KEY_SERVER, value.server)
                    .putString(KEY_SCHOOLNAME, value.schoolname)
                    .putString(KEY_USERNAME, value.username)
                    .putString(KEY_SESSION_ID, value.sessionId)
                    .putInt(KEY_PERSON_ID, value.personId)
                    .putInt(KEY_CLASS_ID, value.classId)
                    .putString(KEY_PERSON_NAME, value.personName)
                    .putInt(KEY_PERSON_TYPE, value.personType)
                    .putLong(KEY_SESSION_TIME, System.currentTimeMillis())
                    .apply()
            }
        }

    var studentId: Int
        get() = prefs.getInt(KEY_STUDENT_ID, 0)
        set(v) = prefs.edit().putInt(KEY_STUDENT_ID, v).apply()

    fun isSessionFresh(): Boolean {
        val ts = prefs.getLong(KEY_SESSION_TIME, 0L)
        if (ts == 0L) return false
        val ageMs = System.currentTimeMillis() - ts
        return ageMs < SESSION_TTL_MS
    }

    fun getSessionTime(): Long = prefs.getLong(KEY_SESSION_TIME, 0L)

    fun touchSession() {
        prefs.edit().putLong(KEY_SESSION_TIME, System.currentTimeMillis()).apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_SERVER).remove(KEY_SCHOOLNAME).remove(KEY_USERNAME)
            .remove(KEY_SESSION_ID).remove(KEY_PERSON_ID).remove(KEY_CLASS_ID)
            .remove(KEY_PERSON_NAME).remove(KEY_PERSON_TYPE).remove(KEY_SESSION_TIME)
            .apply()
        // Credentials and second account are in plainPrefs
        plainPrefs.edit()
            .remove(KEY_STORED_USER).remove(KEY_STORED_PASS).remove(KEY_STUDENT_ID)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        plainPrefs.edit().clear().apply()
    }

    var storedCredentials: Pair<String, String>?
        get() {
            // Stored in plainPrefs for reliability on API 29 where EncryptedSharedPreferences
            // may silently fail on first install. Credentials are already protected by
            // Android's app sandbox; full-disk encryption protects them at rest.
            val u = plainPrefs.getString(KEY_STORED_USER, null) ?: return null
            val p = plainPrefs.getString(KEY_STORED_PASS, null) ?: return null
            return Pair(u, p)
        }
        set(value) {
            if (value == null) {
                plainPrefs.edit().remove(KEY_STORED_USER).remove(KEY_STORED_PASS).apply()
            } else {
                plainPrefs.edit()
                    .putString(KEY_STORED_USER, value.first)
                    .putString(KEY_STORED_PASS, value.second)
                    .apply()
            }
        }

    data class SecondAccount(
        val username: String,
        val password: String,
        val label: String,
        val personType: Int = 0,
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
            // Also in plainPrefs for the same reliability reason as storedCredentials
            val u = plainPrefs.getString(KEY_SECOND_USER, null) ?: return null
            val p = plainPrefs.getString(KEY_SECOND_PASS, null) ?: return null
            val l = plainPrefs.getString(KEY_SECOND_LABEL, "") ?: ""
            val t = plainPrefs.getInt(KEY_SECOND_TYPE, 0)
            val n = plainPrefs.getString(KEY_SECOND_NAME, "") ?: ""
            return SecondAccount(u, p, l, t, n)
        }
        set(value) {
            if (value == null) {
                plainPrefs.edit()
                    .remove(KEY_SECOND_USER).remove(KEY_SECOND_PASS)
                    .remove(KEY_SECOND_LABEL).remove(KEY_SECOND_TYPE)
                    .remove(KEY_SECOND_NAME)
                    .apply()
            } else {
                plainPrefs.edit()
                    .putString(KEY_SECOND_USER, value.username)
                    .putString(KEY_SECOND_PASS, value.password)
                    .putString(KEY_SECOND_LABEL, value.label)
                    .putInt(KEY_SECOND_TYPE, value.personType)
                    .putString(KEY_SECOND_NAME, value.personName)
                    .apply()
            }
        }

    var timetableDays: Int
        get() = plainPrefs.getInt(KEY_TIMETABLE_DAYS, DEFAULT_TIMETABLE_DAYS)
        set(value) {
            plainPrefs.edit()
                .putInt(KEY_TIMETABLE_DAYS, value.coerceIn(MIN_TIMETABLE_DAYS, MAX_TIMETABLE_DAYS))
                .apply()
        }

    var showLongSubjects: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_LONG_SUBJECTS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_LONG_SUBJECTS, value).apply() }

    var showLongTeachers: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_LONG_TEACHERS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_LONG_TEACHERS, value).apply() }

    var showLongRooms: Boolean
        get() = plainPrefs.getBoolean(KEY_SHOW_LONG_ROOMS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_SHOW_LONG_ROOMS, value).apply() }

    var useCompactWeekView: Boolean
        get() = plainPrefs.getBoolean(KEY_USE_COMPACT_WEEK_VIEW, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_USE_COMPACT_WEEK_VIEW, value).apply() }

    var cacheTtlMinutes: Int
        get() = plainPrefs.getInt(KEY_CACHE_TTL, DEFAULT_CACHE_TTL)
        set(value) {
            plainPrefs.edit()
                .putInt(KEY_CACHE_TTL, value.coerceIn(MIN_CACHE_TTL, MAX_CACHE_TTL))
                .apply()
        }

    fun isCacheFresh(lastFetchMs: Long): Boolean {
        val ttl = cacheTtlMinutes
        if (ttl == 0 || lastFetchMs == 0L) return false
        return (System.currentTimeMillis() - lastFetchMs) < ttl * 60_000L
    }

    private val plainPrefs by lazy {
        context.getSharedPreferences("webuntis_settings", android.content.Context.MODE_PRIVATE)
    }


    // ─── EXPORT / IMPORT ──────────────────────────────────────────────────────

    /**
     * Exports all non-session settings as a JSON string.
     * Sensitive fields (passwords) are included so import restores a fully
     * working configuration. The user is responsible for keeping the file safe.
     */
    fun exportSettings(): String {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val obj  = com.google.gson.JsonObject().apply {
            addProperty("version", 1)
            // Primary account
            session?.let { s ->
                addProperty("server",     s.server)
                addProperty("schoolname", s.schoolname)
                addProperty("username",   s.username)
                addProperty("personName", s.personName)
                addProperty("personType", s.personType)
            }
            storedCredentials?.let { addProperty("password", it.second) }
            // Second account
            secondAccount?.let { acc ->
                val sa = com.google.gson.JsonObject().apply {
                    addProperty("username",   acc.username)
                    addProperty("password",   acc.password)
                    addProperty("label",      acc.label)
                    addProperty("personType", acc.personType)
                    addProperty("personName", acc.personName)
                }
                add("secondAccount", sa)
            }
            // UI / timetable settings
            addProperty("timetableDays",       timetableDays)
            addProperty("showLongSubjects",    showLongSubjects)
            addProperty("showLongTeachers",    showLongTeachers)
            addProperty("showLongRooms",       showLongRooms)
            addProperty("useCompactWeekView",  useCompactWeekView)
            addProperty("cacheTtlMinutes",     cacheTtlMinutes)
        }
        return gson.toJson(obj)
    }

    /**
     * Imports settings from a JSON string previously produced by [exportSettings].
     * Returns a [ImportResult] describing what was restored.
     * Does NOT trigger a re-login — the caller must do that.
     */
    fun importSettings(json: String): ImportResult {
        return try {
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            var primaryUpdated = false
            var secondUpdated  = false

            // Primary credentials
            val server     = obj.get("server")?.asString
            val schoolname = obj.get("schoolname")?.asString
            val username   = obj.get("username")?.asString
            val password   = obj.get("password")?.asString
            val personName = obj.get("personName")?.asString ?: ""
            val personType = obj.get("personType")?.asInt ?: 0

            if (!server.isNullOrBlank() && !schoolname.isNullOrBlank() &&
                !username.isNullOrBlank() && !password.isNullOrBlank()) {
                // Store credentials — re-login must be triggered by caller
                storedCredentials = Pair(username, password)
                // Update session metadata (server/schoolname/username) so Settings UI shows them
                session = session?.copy(
                    server     = server,
                    schoolname = schoolname,
                    username   = username,
                    personName = personName,
                    personType = personType
                ) ?: com.webuntis.dashboard.model.SessionData(
                    server     = server,
                    schoolname = schoolname,
                    username   = username,
                    sessionId  = "",
                    personId   = 0,
                    classId    = 0,
                    personName = personName,
                    personType = personType
                )
                primaryUpdated = true
            }

            // Second account
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

            // UI settings
            obj.get("timetableDays")?.asInt?.let      { timetableDays      = it }
            obj.get("showLongSubjects")?.asBoolean?.let { showLongSubjects = it }
            obj.get("showLongTeachers")?.asBoolean?.let { showLongTeachers = it }
            obj.get("showLongRooms")?.asBoolean?.let    { showLongRooms    = it }
            obj.get("useCompactWeekView")?.asBoolean?.let { useCompactWeekView = it }
            obj.get("cacheTtlMinutes")?.asInt?.let    { cacheTtlMinutes    = it }

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
        private const val KEY_STORED_USER  = "stored_user"
        private const val KEY_STORED_PASS  = "stored_pass"
        private const val KEY_SECOND_USER  = "second_user"
        private const val KEY_SECOND_PASS  = "second_pass"
        private const val KEY_SECOND_LABEL = "second_label"
        private const val KEY_SECOND_TYPE  = "second_type"
        private const val KEY_SECOND_NAME  = "second_name"
        private const val KEY_TIMETABLE_DAYS = "timetable_days"
        private const val KEY_STUDENT_ID     = "student_id"
        private const val KEY_USE_COMPACT_WEEK_VIEW = "use_compact_week_view"
        const val DEFAULT_TIMETABLE_DAYS = 5
        const val MIN_TIMETABLE_DAYS     = 1
        const val MAX_TIMETABLE_DAYS     = 20
        private const val KEY_CACHE_TTL      = "cache_ttl_minutes"
        private const val KEY_SHOW_LONG_SUBJECTS = "show_long_subjects"
        private const val KEY_SHOW_LONG_TEACHERS = "show_long_teachers"
        private const val KEY_SHOW_LONG_ROOMS    = "show_long_rooms"
        const val DEFAULT_CACHE_TTL      = 5
        const val MIN_CACHE_TTL          = 0
        const val MAX_CACHE_TTL          = 60
        private const val KEY_SESSION_TIME = "session_time"
        private const val SESSION_TTL_MS   = 45 * 60 * 1000L
    }
}
