package com.webuntis.dashboard.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavDeepLinkBuilder
import com.webuntis.dashboard.MainActivity
import com.webuntis.dashboard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the local notifications shown by [PlanChangeCheckWorker] (schedule changes, new
 * messages, new homework, new classbook entries). One channel per category so the user can
 * mute/tune each kind individually in the system settings, plus a fallback/default channel.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_TIMETABLE = "channel_timetable_changes"
        const val CHANNEL_MESSAGES  = "channel_new_messages"
        const val CHANNEL_HOMEWORK  = "channel_new_homework"
        const val CHANNEL_CLASSBOOK = "channel_new_classbook"

        private const val ID_TIMETABLE_BASE = 10_000
        private const val ID_MESSAGES       = 20_000
        private const val ID_HOMEWORK       = 20_001
        private const val ID_CLASSBOOK      = 20_002
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_TIMETABLE,
                    context.getString(R.string.notif_channel_timetable),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.notif_channel_timetable_desc) },
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.notif_channel_messages),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.notif_channel_messages_desc) },
                NotificationChannel(
                    CHANNEL_HOMEWORK,
                    context.getString(R.string.notif_channel_homework),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.notif_channel_homework_desc) },
                NotificationChannel(
                    CHANNEL_CLASSBOOK,
                    context.getString(R.string.notif_channel_classbook),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.notif_channel_classbook_desc) }
            )
        )
    }

    /** True once the user has actually granted the runtime permission (or it's not needed
     *  pre-Android 13) — callers should skip posting rather than crash if this is false. */
    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Opens the app straight at [destinationId] in nav_graph when the notification is tapped. */
    private fun contentIntent(destinationId: Int): PendingIntent =
        NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(destinationId)
            .setComponentName(MainActivity::class.java)
            .createPendingIntent()

    private fun notify(id: Int, notification: android.app.Notification) {
        if (!hasPermission()) return
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and this call — nothing to do.
        }
    }

    /** One notification per changed lesson (cancelled / substitution / room change). */
    fun notifyLessonChange(offset: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_TIMETABLE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(R.id.timetableFragment))
            .build()
        notify(ID_TIMETABLE_BASE + offset, notification)
    }

    /** Collapsed summary used instead of per-item notifications when many lessons changed at
     *  once (e.g. a whole day reorganized) — avoids flooding the notification shade. */
    fun notifyLessonChangesSummary(count: Int) {
        val text = context.resources.getQuantityString(R.plurals.notif_timetable_summary, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_TIMETABLE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_timetable_summary_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(R.id.timetableFragment))
            .build()
        notify(ID_TIMETABLE_BASE, notification)
    }

    fun notifyNewMessages(count: Int, singleSubject: String?) {
        val text = if (count == 1 && singleSubject != null) singleSubject
        else context.resources.getQuantityString(R.plurals.notif_messages_summary, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_messages_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(R.id.messagesFragment))
            .build()
        notify(ID_MESSAGES, notification)
    }

    fun notifyNewHomework(count: Int, singleSubject: String?) {
        val text = if (count == 1 && singleSubject != null) singleSubject
        else context.resources.getQuantityString(R.plurals.notif_homework_summary, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_HOMEWORK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_homework_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(R.id.homeworkFragment))
            .build()
        notify(ID_HOMEWORK, notification)
    }

    fun notifyNewClassbookEntries(count: Int, singleSubject: String?) {
        val text = if (count == 1 && singleSubject != null) singleSubject
        else context.resources.getQuantityString(R.plurals.notif_classbook_summary, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_CLASSBOOK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_classbook_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(R.id.classbookFragment))
            .build()
        notify(ID_CLASSBOOK, notification)
    }
}