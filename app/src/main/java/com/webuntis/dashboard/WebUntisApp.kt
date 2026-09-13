package com.webuntis.dashboard

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.webuntis.dashboard.api.NotificationScheduler
import com.webuntis.dashboard.api.SessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WebUntisApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionManager: SessionManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Resume the periodic background check across process restarts (WorkManager itself
        // persists the schedule, but re-asserting it here is cheap/idempotent and guards
        // against it having been cleared, e.g. after an app update).
        if (sessionManager.notificationsEnabled) {
            NotificationScheduler.start(this)
        }
    }
}
