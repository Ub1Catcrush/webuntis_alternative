package com.webuntis.dashboard

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.webuntis.dashboard.api.AppForegroundEvents
import com.webuntis.dashboard.api.NotificationScheduler
import com.webuntis.dashboard.api.SessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WebUntisApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var appForegroundEvents: AppForegroundEvents

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
        registerActivityLifecycleCallbacks(ForegroundRefreshTracker())
    }

    /**
     * Detects "app was in the background for a while, then came back to the foreground" and
     * reacts to it, since data (timetable, messages, ...) fetched before backgrounding can be
     * stale by the time the user returns — nothing currently re-fetches it just because the
     * app became visible again.
     *
     * Counts started/stopped activities rather than reacting to any single onStart/onStop:
     * with one Activity + Fragments/Navigation, transient transitions (e.g. a share sheet or
     * the system permission dialog opening on top) also fire onStop/onStart, and those aren't
     * "the user left and came back" — only a stop-count reaching 0 is. A config change (e.g.
     * rotation) also passes through onStop/onStart even with nothing backgrounded at all,
     * which [Activity.isChangingConfigurations] lets us tell apart from a real backgrounding.
     */
    private inner class ForegroundRefreshTracker : ActivityLifecycleCallbacksAdapter() {
        private var startedCount = 0
        private var backgroundedAt: Long? = null
        /** True until the very first onActivityStarted — a cold process start should trigger an
         *  immediate check too (see checkAndNotify below), not just a resume-from-background,
         *  otherwise changes that happened while the app was fully killed only surface once the
         *  next periodic slot (up to 15 min) happens to run. */
        private var isFirstStart = true

        override fun onActivityStarted(activity: Activity) {
            startedCount++
            if (startedCount != 1) return
            val coldStart = isFirstStart
            isFirstStart = false
            val bgAt = backgroundedAt
            backgroundedAt = null
            if (!coldStart && (bgAt == null || System.currentTimeMillis() - bgAt < FOREGROUND_REFRESH_THRESHOLD_MS)) return

            appForegroundEvents.notifyForegroundResume()
            checkAndNotify()
        }

        override fun onActivityStopped(activity: Activity) {
            startedCount--
            if (startedCount == 0 && !activity.isChangingConfigurations) {
                backgroundedAt = System.currentTimeMillis()
            }
        }
    }

    /** Kicks the change-check worker immediately instead of waiting for its next periodic slot
     *  (up to 15 min) — otherwise a change that happened while backgrounded, or while the app
     *  was fully killed (cold start), wouldn't be notified about until well after the user is
     *  already back in the app and would rather just see it on screen. See also
     *  MainActivity.checkAndShowUnseenChanges(), which surfaces the result in-app as a backup
     *  for whenever the OS/ROM suppresses the system notification outright. */
    private fun checkAndNotify() {
        if (sessionManager.notificationsEnabled) {
            NotificationScheduler.checkNow(this)
        }
    }

    companion object {
        private const val FOREGROUND_REFRESH_THRESHOLD_MS = 60_000L // 1 minute
    }
}

/** Default no-op implementations for the callbacks ForegroundRefreshTracker doesn't need. */
private open class ActivityLifecycleCallbacksAdapter : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

