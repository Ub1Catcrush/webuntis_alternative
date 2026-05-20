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
            // Fallback to regular prefs if encryption fails
            context.getSharedPreferences("webuntis_session_plain", Context.MODE_PRIVATE)
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
                prefs.edit().clear().apply()
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
                    .apply()
            }
        }

    fun clearSession() {
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
    ) {
        val accountTypeLabel: String get() = when (personType) {
            2    -> "Lehrer"
            5    -> "Schüler"
            12   -> "Eltern"
            else -> ""
        }
    }

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
    }
}
