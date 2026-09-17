package com.webuntis.dashboard.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signals "the app just came back to the foreground after being in the background for a
 * while" — see [WebUntisApp]'s ActivityLifecycleCallbacks, which is what actually detects
 * that transition and calls [notifyForegroundResume]. Screens that show data which can go
 * stale in the background (timetable, messages, homework, classbook, absences, events) collect
 * [onForegroundResume] and force-refresh when it fires, instead of only refreshing on manual
 * pull-to-refresh or when a fragment happens to be recreated.
 *
 * A plain SharedFlow (not LiveData/StateFlow) on purpose: this is a one-off event, not a
 * piece of state a late collector should immediately replay — a screen that starts collecting
 * after the moment has passed shouldn't force-refresh retroactively.
 */
@Singleton
class AppForegroundEvents @Inject constructor() {
    private val _onForegroundResume = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onForegroundResume: SharedFlow<Unit> = _onForegroundResume

    fun notifyForegroundResume() {
        _onForegroundResume.tryEmit(Unit)
    }
}
