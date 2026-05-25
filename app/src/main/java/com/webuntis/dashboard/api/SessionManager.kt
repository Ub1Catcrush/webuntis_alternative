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
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "webuntis_session", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("SessionManager",
                "EncryptedSharedPreferences unavailable — credentials will not be persisted", e)
            object : android.content.SharedPreferences {
                private val mem = mutableMapOf<String, Any?>()
                override fun getAll() = mem
                override fun getString(k: String, d: String?) = mem[k] as? String ?: d
                override fun getStringSet(k: String, d: MutableSet<String>?) = d
                override fun getInt(k: String, d: Int) = mem[k] as? Int ?: d
                override fun getLong(k: String, d: Long) = mem[k] as? Long ?: d
                override fun getFloat(k: String, d: Float) = mem[k] as? Float ?: d
                override fun getBoolean(k: String, d: Boolean) = mem[k] as? Boolean ?: d
                override fun contains(k: String) = mem.containsKey(k)
                override fun edit() = object : android.content.SharedPreferences.Editor {
                    override fun putString(k: String, v: String?) = apply { mem[k] = v }
                    override fun putStringSet(k: String, v: MutableSet<String>?) = apply { mem[k] = v }
                    override fun putInt(k: String, v: Int) = apply { mem[k] = v }
                    override fun putLong(k: String, v: Long) = apply { mem[k] = v }
                    override fun putFloat(k: String, v: Float) = apply { mem[k] = v }
                    override fun putBoolean(k: String, v: Boolean) = apply { mem[k] = v }
                    override fun remove(k: String) = apply { mem.remove(k) }
                    override fun clear() = apply { mem.clear() }
                    override fun commit() = true
                    override fun apply() {}
                }
                override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
                override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
            }
        }
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
            .remove(KEY_PERSON_NAME).remove(KEY_PERSON_TYPE).remove(KEY_STORED_USER)
            .remove(KEY_STORED_PASS).remove(KEY_SESSION_TIME).remove(KEY_STUDENT_ID)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

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

    data class SecondAccount(
        val username: String,
        val password: String,
        val label: String,
        val personType: Int = 0,
        val personName: String = ""
    )

    var secondAccount: SecondAccount?
        get() {
            val u = prefs.getString(KEY_SECOND_USER, null) ?: return null
            val p = prefs.getString(KEY_SECOND_PASS, null) ?: return null
            val l = prefs.getString(KEY_SECOND_LABEL, "") ?: ""
            val t = prefs.getInt(KEY_SECOND_TYPE, 0)
            val n = prefs.getString(KEY_SECOND_NAME, "") ?: ""
            return SecondAccount(u, p, l, t, n)
        }
        set(value) {
            if (value == null) {
                prefs.edit()
                    .remove(KEY_SECOND_USER).remove(KEY_SECOND_PASS)
                    .remove(KEY_SECOND_LABEL).remove(KEY_SECOND_TYPE)
                    .remove(KEY_SECOND_NAME)
                    .apply()
            } else {
                prefs.edit()
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
