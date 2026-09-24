package com.webuntis.dashboard.ui.common

import android.content.Context
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.ActiveAccountManager

/**
 * Wires the shared "switch child" toolbar action (see menu_account_switcher.xml) into a
 * fragment's toolbar — used identically by Timetable, Absences, Homework, Classbook and
 * Events, the five screens whose data is scoped to whichever account is "active" for browsing
 * (see ActiveAccountManager / WebUntisRepository.getAuthHeader). Does nothing (and leaves the
 * action hidden, per its default in menu_account_switcher.xml) when the user hasn't added any
 * additional (child) accounts, since there's nothing to switch between — most users will never
 * see this at all.
 */
fun MaterialToolbar.setupAccountSwitcher(activeAccountManager: ActiveAccountManager) {
    if (activeAccountManager.additionalAccounts.isEmpty()) return
    menu.findItem(R.id.action_switch_account)?.isVisible = true
    setOnMenuItemClickListener { item ->
        if (item.itemId == R.id.action_switch_account) {
            showAccountSwitcherDialog(context, activeAccountManager)
            true
        } else false
    }
}

fun showAccountSwitcherDialog(context: Context, activeAccountManager: ActiveAccountManager) {
    // Index 0 is always the primary account (key = null); indices 1..N map 1:1 to
    // activeAccountManager.additionalAccounts.
    val keys: List<String?> = listOf(null) + activeAccountManager.additionalAccounts.map { it.key }
    val labels = keys.map { activeAccountManager.labelFor(it) }.toTypedArray()
    val currentIndex = keys.indexOf(activeAccountManager.current.value).coerceAtLeast(0)

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.account_switcher_title)
        .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
            activeAccountManager.switchTo(keys[which])
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
