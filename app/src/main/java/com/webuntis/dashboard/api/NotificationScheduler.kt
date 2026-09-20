package com.webuntis.dashboard.api

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues/cancels the periodic [PlanChangeCheckWorker] job. 15 minutes is Android's hard
 * floor for periodic WorkManager work — anything shorter is silently clamped to 15 min by the
 * OS anyway, so that's the shortest interval actually achievable here. Actual execution timing
 * beyond that is up to the OS (Doze/App Standby), not something the app controls directly.
 */
object NotificationScheduler {
    private const val UNIQUE_WORK_NAME = "plan_change_check"
    const val IMMEDIATE_CHECK_WORK_NAME = "plan_change_check_immediate"

    fun start(context: Context) {
        val request = PeriodicWorkRequestBuilder<PlanChangeCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Runs the check exactly once, immediately, outside the periodic schedule — used by the
     * "Jetzt prüfen" button in Settings so a user (or a bug report) can verify the whole
     * pipeline works right now instead of having to wait up to 15 minutes and having no way to
     * tell whether a lack of notification means "nothing changed" or "the pipeline is broken".
     * Returns the enqueued request's ID so the caller can observe its [androidx.work.WorkInfo].
     */
    fun checkNow(context: Context): java.util.UUID {
        val request = androidx.work.OneTimeWorkRequestBuilder<PlanChangeCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_CHECK_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }
}
