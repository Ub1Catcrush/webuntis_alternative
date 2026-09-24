package com.webuntis.dashboard.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive wrapper around [SessionManager.activeAccountKey] — which account's data
 * (timetable, absences, homework, classbook, events) is currently being browsed. Null means
 * the primary account; otherwise it's the [SessionManager.SecondAccount.key] of one of
 * [SessionManager.additionalAccounts].
 *
 * SessionManager's own property is a plain SharedPreferences-backed get/set with no way for a
 * ViewModel to know it changed. This adds a [StateFlow] on top so every screen showing
 * per-student data can collect [current] and reload when it changes, instead of each needing
 * its own polling or a separate broadcast mechanism (see how each such ViewModel already
 * collects AppForegroundEvents for a comparable pattern).
 *
 * Does NOT affect which account a message is sent/composed as — that's chosen per-message in
 * MessagesViewModel/Fragment instead, independent of which student's schedule you're viewing.
 */
@Singleton
class ActiveAccountManager @Inject constructor(
    private val sessionManager: SessionManager
) {
    private val _current = MutableStateFlow(sessionManager.activeAccountKey)
    val current: StateFlow<String?> = _current

    fun switchTo(key: String?) {
        sessionManager.activeAccountKey = key
        _current.value = key
    }

    /** All configured additional (child) accounts, exposed here so callers (the account
     *  switcher UI) don't each need their own separate SessionManager injection just for this. */
    val additionalAccounts: List<SessionManager.SecondAccount> get() = sessionManager.additionalAccounts

    /** Human-readable label for a given key ("Hauptaccount" for null, the child's label otherwise). */
    fun labelFor(key: String?): String {
        if (key == null) {
            val session = sessionManager.session
            return session?.personName?.takeIf { it.isNotBlank() } ?: session?.accountTypeLabel ?: "Hauptaccount"
        }
        val account = sessionManager.additionalAccounts.firstOrNull { it.key == key } ?: return key
        return account.label.ifBlank { account.personName.ifBlank { account.username } }
    }
}
