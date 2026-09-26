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

        /**
         * Parses a persisted snapshot, tolerating both invalid JSON and JSON written by an
         * older app version that predates one of the fields above. Gson fills a missing JSON
         * key with a raw Java `null` via reflection — it never runs the constructor, so it
         * skips the `= emptyList()`/`= emptyMap()` defaults entirely and can hand back a
         * ChangeSnapshot whose fields are null despite their Kotlin type being non-nullable.
         * Every direct field read (`snapshot.recentChanges.any {...}` etc.) was one such
         * legacy/partial blob away from an NPE. Route every parse through here instead of
         * calling Gson directly so that guarantee actually holds everywhere.
         */
        fun parse(json: String?, gson: com.google.gson.Gson = com.google.gson.Gson()): ChangeSnapshot? {
            if (json.isNullOrBlank()) return null
            return try {
                val raw = gson.fromJson(json, ChangeSnapshot::class.java) ?: return null
                // Read into explicitly-nullable locals first: assigning a (statically) non-null
                // property into a nullable-typed local is always legal in Kotlin and preserves
                // whatever the value actually is at runtime, letting `?:` below catch the case
                // where Gson left it null despite the static type promising otherwise.
                val lessonStatus: Map<String, String>? = raw.lessonStatus
                val messageIds: Set<Int>? = raw.messageIds
                val homeworkIds: Set<Int>? = raw.homeworkIds
                val classbookIds: Set<Int>? = raw.classbookIds
                val notifiedAt: Map<String, Long>? = raw.notifiedAt
                val recentChanges: List<ChangeLogEntry>? = raw.recentChanges
                raw.copy(
                    lessonStatus  = lessonStatus  ?: emptyMap(),
                    messageIds    = messageIds    ?: emptySet(),
                    homeworkIds   = homeworkIds   ?: emptySet(),
                    classbookIds  = classbookIds  ?: emptySet(),
                    notifiedAt    = notifiedAt    ?: emptyMap(),
                    recentChanges = recentChanges ?: emptyList()
                )
            } catch (e: Exception) { null }
        }
    }
}

/** One entry in the human-readable change log shown by RecentChangesDialogFragment. */
data class ChangeLogEntry(
    val category: String, // "timetable" | "messages" | "homework" | "classbook"
    val title: String,
    val text: String,
    val timestampMs: Long
)
