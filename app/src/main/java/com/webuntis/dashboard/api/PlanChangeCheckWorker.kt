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

            val previous = sessionManager.lastNotifiedSnapshot?.let { json ->
                try { gson.fromJson(json, ChangeSnapshot::class.java) } catch (e: Exception) { null }
            }
            val isFirstRun = previous == null
            val baseline = previous ?: ChangeSnapshot()

            val lessonStatus = checkTimetable(baseline, notify = !isFirstRun)
            val messageIds   = checkMessages(baseline, notify = !isFirstRun)
            val homeworkIds  = checkHomework(baseline, notify = !isFirstRun)
            val classbookIds = checkClassbook(baseline, notify = !isFirstRun)

            sessionManager.lastNotifiedSnapshot = gson.toJson(
                ChangeSnapshot(lessonStatus, messageIds, homeworkIds, classbookIds)
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(tag, "Background check failed, will retry next cycle", e)
            // Not Result.retry(): a failed network call here shouldn't cause WorkManager's
            // backoff to spin the job more often than its normal periodic interval.
            Result.success()
        }
    }

    private fun lessonKey(lesson: Lesson): String = "${lesson.date}-${lesson.startTime}-${lesson.subjectName}"
    private fun lessonState(lesson: Lesson): String = when {
        lesson.isCancelled    -> "cancelled"
        lesson.isSubstitution -> "subst"
        lesson.isRoomChange   -> "roomchange"
        else                  -> "normal"
    }

    /** Only lessons in the near future are worth notifying about — a change to a lesson from
     *  last week (e.g. re-fetched while backfilling enrichment) shouldn't resurface. */
    private suspend fun checkTimetable(baseline: ChangeSnapshot, notify: Boolean): Map<String, String> {
        val days = repository.getSchoolDaysFrom(LocalDate.now(), numDays = 5, forceRefresh = true)
            .getOrNull() ?: return baseline.lessonStatus
        val lessons = days.flatMap { it.lessons }

        val current = lessons.associate { lessonKey(it) to lessonState(it) }
        if (notify) {
            val changed = current.filter { (key, state) ->
                state != "normal" && baseline.lessonStatus[key] != state
            }
            val bySubjectLesson = lessons.associateBy { lessonKey(it) }
            if (changed.size > 4) {
                notificationHelper.notifyLessonChangesSummary(changed.size)
            } else {
                changed.entries.forEachIndexed { i, (key, state) ->
                    val lesson = bySubjectLesson[key] ?: return@forEachIndexed
                    val title = when (state) {
                        "cancelled"  -> "${lesson.subjectName} fällt aus"
                        "subst"      -> "Vertretung: ${lesson.subjectName}"
                        "roomchange" -> "Raumänderung: ${lesson.subjectName}"
                        else         -> lesson.subjectName
                    }
                    val text = lesson.displayRooms(true).takeIf { it.isNotEmpty() }
                        ?.let { "Raum $it" } ?: ""
                    notificationHelper.notifyLessonChange(i, title, text)
                }
            }
        }
        // Only keep near-future keys going forward — an unbounded map would grow forever
        // as dates roll by.
        return current
    }

    private suspend fun checkMessages(baseline: ChangeSnapshot, notify: Boolean): Set<Int> {
        val messages = repository.getMessages(forceRefresh = true).getOrNull() ?: return baseline.messageIds
        val currentIds = messages.map { it.id }.toSet()
        if (notify) {
            val newOnes = messages.filter { it.id !in baseline.messageIds }
            if (newOnes.isNotEmpty()) {
                notificationHelper.notifyNewMessages(newOnes.size, newOnes.singleOrNull()?.subject)
            }
        }
        return currentIds
    }

    private suspend fun checkHomework(baseline: ChangeSnapshot, notify: Boolean): Set<Int> {
        val homework = repository.getHomework(forceRefresh = true).getOrNull()?.first ?: return baseline.homeworkIds
        val currentIds = homework.map { it.id }.toSet()
        if (notify) {
            val newOnes = homework.filter { it.id !in baseline.homeworkIds }
            if (newOnes.isNotEmpty()) {
                notificationHelper.notifyNewHomework(newOnes.size, newOnes.singleOrNull()?.subject)
            }
        }
        return currentIds
    }

    private suspend fun checkClassbook(baseline: ChangeSnapshot, notify: Boolean): Set<Int> {
        val entries = repository.getClassbookEntries(forceRefresh = true).getOrNull() ?: return baseline.classbookIds
        val currentIds = entries.map { it.id }.toSet()
        if (notify) {
            val newOnes = entries.filter { it.id !in baseline.classbookIds }
            if (newOnes.isNotEmpty()) {
                notificationHelper.notifyNewClassbookEntries(newOnes.size, newOnes.singleOrNull()?.subject)
            }
        }
        return currentIds
    }
}
