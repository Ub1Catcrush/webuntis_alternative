package com.webuntis.dashboard.api

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.webuntis.dashboard.model.Lesson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/**
 * Periodic background check (see [NotificationScheduler]) that looks for schedule changes,
 * new messages, new homework, and new classbook entries since the last check, and posts a
 * local notification for anything new — see [NotificationHelper].
 *
 * Entirely local-diff based: there is no push backend here (WebUntis doesn't offer one to
 * third-party apps), so this polls on a periodic WorkManager job instead. Deliberately
 * conservative about network/battery use — a single combined check per run, skips entirely
 * when the feature is off or no session/credentials are available, and the very first run
 * only establishes a baseline (no notification flood for things that already existed before
 * the feature was turned on).
 *
 * Dedup strategy (see [ChangeSnapshot]): a per-category "current state" comparison decides
 * *what* changed, but whether that change actually triggers a notification is gated by the
 * [ChangeSnapshot.notifiedAt] ledger, which remembers every key we've already notified about
 * for 7 days. The snapshot is persisted after *each* category, not just once at the end of
 * [doWork] — so if e.g. the classbook check throws, the timetable/messages/homework results
 * already computed this run are not lost and won't be re-notified next cycle.
 */
@HiltWorker
class PlanChangeCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: WebUntisRepository,
    private val sessionManager: SessionManager,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    private val gson = Gson()
    private val tag = "PlanChangeCheckWorker"

    override suspend fun doWork(): Result {
        if (!sessionManager.notificationsEnabled) return Result.success()
        // No session, or never logged in with "remember me" — nothing we can silently
        // re-authenticate with in the background (see SessionManager.storedCredentials).
        if (sessionManager.session == null || sessionManager.storedCredentials == null) {
            return Result.success()
        }

        return try {
            notificationHelper.ensureChannels()

            val previous = readSnapshot()
            val isFirstRun = previous == null
            // Mutable "working copy" we persist after every category, so a failure partway
            // through doesn't roll back categories that already succeeded this run.
            var snapshot = pruneExpired(previous ?: ChangeSnapshot())

            snapshot = checkTimetable(snapshot, notify = !isFirstRun)
            persist(snapshot)

            snapshot = checkMessages(snapshot, notify = !isFirstRun)
            persist(snapshot)

            snapshot = checkHomework(snapshot, notify = !isFirstRun)
            persist(snapshot)

            snapshot = checkClassbook(snapshot, notify = !isFirstRun)
            persist(snapshot)

            Result.success()
        } catch (e: Exception) {
            Log.w(tag, "Background check failed, will retry next cycle", e)
            // Not Result.retry(): a failed network call here shouldn't cause WorkManager's
            // backoff to spin the job more often than its normal periodic interval. Whatever
            // was already persisted via persist() above (per-category) survives regardless.
            Result.success()
        }
    }

    private fun readSnapshot(): ChangeSnapshot? =
        sessionManager.lastNotifiedSnapshot?.let { json ->
            try { gson.fromJson(json, ChangeSnapshot::class.java) } catch (e: Exception) { null }
        }

    private fun persist(snapshot: ChangeSnapshot) {
        sessionManager.lastNotifiedSnapshot = gson.toJson(snapshot)
    }

    /** Drops ledger/log entries older than the 7-day TTL so the persisted blob doesn't grow
     *  forever and so a key becomes eligible for a fresh notification again after 7 days. */
    private fun pruneExpired(snapshot: ChangeSnapshot): ChangeSnapshot {
        val cutoff = System.currentTimeMillis() - ChangeSnapshot.NOTIFIED_TTL_MS
        return snapshot.copy(
            notifiedAt = snapshot.notifiedAt.filterValues { it >= cutoff },
            recentChanges = snapshot.recentChanges
                .filter { it.timestampMs >= cutoff }
                .sortedByDescending { it.timestampMs }
                .take(ChangeSnapshot.MAX_RECENT_CHANGES)
        )
    }

    /** True if we have NOT already notified about [key] within the last 7 days. */
    private fun ChangeSnapshot.isFresh(key: String): Boolean {
        val last = notifiedAt[key] ?: return true
        return System.currentTimeMillis() - last >= ChangeSnapshot.NOTIFIED_TTL_MS
    }

    private fun ChangeSnapshot.withNotified(keys: List<String>, log: List<ChangeLogEntry>): ChangeSnapshot {
        if (keys.isEmpty() && log.isEmpty()) return this
        val now = System.currentTimeMillis()
        return copy(
            notifiedAt = notifiedAt + keys.associateWith { now },
            recentChanges = (log + recentChanges)
                .sortedByDescending { it.timestampMs }
                .take(ChangeSnapshot.MAX_RECENT_CHANGES)
        )
    }

    // Stable-ish key: server-side lesson id plus date. Deliberately does NOT include
    // subjectName — that can be represented differently between polls for a substituted /
    // cancelled lesson (e.g. original vs. replacement subject), which previously made the
    // same real-world change look "new" again on the next check and re-notify endlessly.
    private fun lessonKey(lesson: Lesson): String = "${lesson.id}-${lesson.date}-${lesson.startTime}"
    private fun lessonState(lesson: Lesson): String = when {
        lesson.isCancelled    -> "cancelled"
        lesson.isSubstitution -> "subst"
        lesson.isRoomChange   -> "roomchange"
        else                  -> "normal"
    }

    /** Only lessons in the near future are worth notifying about — a change to a lesson from
     *  last week (e.g. re-fetched while backfilling enrichment) shouldn't resurface. */
    private suspend fun checkTimetable(baseline: ChangeSnapshot, notify: Boolean): ChangeSnapshot {
        val days = repository.getSchoolDaysFrom(LocalDate.now(), numDays = 5, forceRefresh = true)
            .getOrNull() ?: return baseline
        val lessons = days.flatMap { it.lessons }

        val current = lessons.associate { lessonKey(it) to lessonState(it) }
        // Only keep near-future keys going forward — an unbounded map would grow forever
        // as dates roll by.
        var result = baseline.copy(lessonStatus = current)
        if (!notify) return result

        val changed = current.filter { (key, state) ->
            state != "normal" && baseline.lessonStatus[key] != state
        }
        // Gate on the 7-day dedup ledger too: a key we already notified about recently is
        // skipped even if it looks "changed" against the (possibly stale/partial) state map.
        val toNotify = changed.filterKeys { key -> baseline.isFresh("timetable:$key") }
        if (toNotify.isEmpty()) return result

        val bySubjectLesson = lessons.associateBy { lessonKey(it) }
        val notifiedKeys = mutableListOf<String>()
        val log = mutableListOf<ChangeLogEntry>()
        val now = System.currentTimeMillis()

        if (toNotify.size > 4) {
            notificationHelper.notifyLessonChangesSummary(toNotify.size)
        }
        toNotify.entries.forEachIndexed { i, (key, state) ->
            val lesson = bySubjectLesson[key] ?: return@forEachIndexed
            val title = when (state) {
                "cancelled"  -> "${lesson.subjectName} fällt aus"
                "subst"      -> "Vertretung: ${lesson.subjectName}"
                "roomchange" -> "Raumänderung: ${lesson.subjectName}"
                else         -> lesson.subjectName
            }
            val text = lesson.displayRooms(true).takeIf { it.isNotEmpty() }
                ?.let { "Raum $it" } ?: ""
            if (toNotify.size <= 4) {
                notificationHelper.notifyLessonChange(i, title, text)
            }
            notifiedKeys += "timetable:$key"
            log += ChangeLogEntry("timetable", title, text, now)
        }
        result = result.withNotified(notifiedKeys, log)
        return result
    }

    private suspend fun checkMessages(baseline: ChangeSnapshot, notify: Boolean): ChangeSnapshot {
        val messages = repository.getMessages(forceRefresh = true).getOrNull() ?: return baseline
        val currentIds = messages.map { it.id }.toSet()
        var result = baseline.copy(messageIds = currentIds)
        if (!notify) return result

        val newOnes = messages.filter { it.id !in baseline.messageIds && baseline.isFresh("message:${it.id}") }
        if (newOnes.isEmpty()) return result

        notificationHelper.notifyNewMessages(newOnes.size, newOnes.singleOrNull()?.subject)
        val now = System.currentTimeMillis()
        result = result.withNotified(
            newOnes.map { "message:${it.id}" },
            newOnes.map { ChangeLogEntry("messages", it.subject.orEmpty().ifBlank { "Neue Nachricht" }, "", now) }
        )
        return result
    }

    private suspend fun checkHomework(baseline: ChangeSnapshot, notify: Boolean): ChangeSnapshot {
        val homework = repository.getHomework(forceRefresh = true).getOrNull()?.first ?: return baseline
        val currentIds = homework.map { it.id }.toSet()
        var result = baseline.copy(homeworkIds = currentIds)
        if (!notify) return result

        val newOnes = homework.filter { it.id !in baseline.homeworkIds && baseline.isFresh("homework:${it.id}") }
        if (newOnes.isEmpty()) return result

        notificationHelper.notifyNewHomework(newOnes.size, newOnes.singleOrNull()?.subject)
        val now = System.currentTimeMillis()
        result = result.withNotified(
            newOnes.map { "homework:${it.id}" },
            newOnes.map { ChangeLogEntry("homework", it.subject.orEmpty().ifBlank { "Neue Hausaufgabe" }, "", now) }
        )
        return result
    }

    private suspend fun checkClassbook(baseline: ChangeSnapshot, notify: Boolean): ChangeSnapshot {
        val entries = repository.getClassbookEntries(forceRefresh = true).getOrNull() ?: return baseline
        val currentIds = entries.map { it.id }.toSet()
        var result = baseline.copy(classbookIds = currentIds)
        if (!notify) return result

        val newOnes = entries.filter { it.id !in baseline.classbookIds && baseline.isFresh("classbook:${it.id}") }
        if (newOnes.isEmpty()) return result

        notificationHelper.notifyNewClassbookEntries(newOnes.size, newOnes.singleOrNull()?.subject)
        val now = System.currentTimeMillis()
        result = result.withNotified(
            newOnes.map { "classbook:${it.id}" },
            newOnes.map { ChangeLogEntry("classbook", it.subject.orEmpty().ifBlank { "Neuer Klassenbucheintrag" }, "", now) }
        )
        return result
    }
}
