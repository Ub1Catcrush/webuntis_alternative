package com.webuntis.dashboard.api

/**
 * Persisted state for [PlanChangeCheckWorker], stored as JSON in
 * [SessionManager.lastNotifiedSnapshot]. Two things are tracked here, deliberately kept
 * separate:
 *
 * 1) [lessonStatus] / [messageIds] / [homeworkIds] / [classbookIds] — the *current* state as of
 *    the last check, used to compute what changed since then (e.g. a lesson going from
 *    "normal" to "cancelled").
 *
 * 2) [notifiedAt] — an append-only ledger of "we already sent a notification for this exact
 *    key" with a timestamp, independent of (1). This is what actually gates whether a
 *    notification is sent: something is only notified once per key within [NOTIFIED_TTL_MS]
 *    (7 days), even if a partial failure elsewhere caused (1) to be recomputed from an older
 *    baseline, or a change transiently drops out of and back into the near-future window.
 *    Entries older than the TTL are pruned on every run so this can't grow forever.
 *
 * [recentChanges] is a small human-readable log (also capped/pruned to 7 days) purely for the
 * in-app "Neuigkeiten" dialog (see RecentChangesDialogFragment) — so the user can see *what*
 * changed, not just a bare notification count.
 */
data class ChangeSnapshot(
    // "yyyyMMdd-HHmm-SubjectShortName" -> "cancelled" | "subst" | "roomchange" | "normal"
    val lessonStatus: Map<String, String> = emptyMap(),
    val messageIds: Set<Int> = emptySet(),
    val homeworkIds: Set<Int> = emptySet(),
    val classbookIds: Set<Int> = emptySet(),
    // Dedup ledger: notification key -> epoch millis when we last notified about it.
    val notifiedAt: Map<String, Long> = emptyMap(),
    // Human-readable log for the in-app "what's new" dialog, newest first.
    val recentChanges: List<ChangeLogEntry> = emptyList()
) {
    companion object {
        /** How long a notified key stays "already notified" (see [notifiedAt]) and how long
         *  entries stay in [recentChanges] before being pruned. */
        const val NOTIFIED_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Upper bound on [recentChanges] regardless of age, so a pathological burst of
         *  changes can't make the persisted blob grow without limit. */
        const val MAX_RECENT_CHANGES = 200
    }
}

/** One entry in the human-readable change log shown by RecentChangesDialogFragment. */
data class ChangeLogEntry(
    val category: String, // "timetable" | "messages" | "homework" | "classbook"
    val title: String,
    val text: String,
    val timestampMs: Long
)
