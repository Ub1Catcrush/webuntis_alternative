package com.webuntis.dashboard.api

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues/cancels the periodic [PlanChangeCheckWorker] job. 30 minutes is the shortest
 * interval WorkManager reliably honors for periodic work close to Android's own minimum
 * (15 min) while leaving real headroom for Doze/battery-saver deferrals — actual execution
 * timing is up to the OS either way.
 */
object NotificationScheduler {
    private const val UNIQUE_WORK_NAME = "plan_change_check"

    fun start(context: Context) {
        val request = PeriodicWorkRequestBuilder<PlanChangeCheckWorker>(30, TimeUnit.MINUTES)
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
}
