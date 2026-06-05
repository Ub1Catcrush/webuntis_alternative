package com.webuntis.dashboard.api

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.webuntis.dashboard.BuildConfig
import com.webuntis.dashboard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val githubService: GithubService
) {
    private val tag = "UpdateManager"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val releaseNotes: String?,
        val downloadUrl: String?
    )

    suspend fun checkForUpdates(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val response = githubService.getLatestRelease()
            if (response.isSuccessful) {
                val release = response.body() ?: return@withContext Result.failure(Exception("Leere Antwort vom Server"))
                val latestVersion = release.tagName.removePrefix("v")
                val currentVersion = BuildConfig.VERSION_NAME

                val hasUpdate = isNewerVersion(currentVersion, latestVersion)
                val apkAsset = release.assets.firstOrNull { it.name.equals("app-release-signed.apk") }

                Result.success(UpdateInfo(
                    hasUpdate = hasUpdate,
                    latestVersion = latestVersion,
                    releaseNotes = release.body,
                    downloadUrl = apkAsset?.downloadUrl
                ))
            } else {
                Result.failure(Exception("Fehler beim Abrufen der Releases: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val size = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until size) {
            val curr = currentParts.getOrNull(i) ?: 0
            val late = latestParts.getOrNull(i) ?: 0
            if (late > curr) return true
            if (curr > late) return false
        }
        return false
    }

    /**
     * Prüft, ob die Berechtigung zur Installation von APKs vorliegt (ab Android O).
     */
    fun checkInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(tag, "Konnte Einstellungs-Seite nicht öffnen", e)
                }
                return false
            }
        }
        return true
    }

    fun downloadAndInstall(url: String, fileName: String) {
        if (!checkInstallPermission()) {
            Toast.makeText(context, context.getString(R.string.settings_update_permission_required), Toast.LENGTH_LONG).show()
            return
        }

        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(url.toUri())
            .setTitle(context.getString(R.string.settings_update_dialog_title_main))
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(destination)
                    context.unregisterReceiver(this)
                }
            }
        }
        
        // Android 14+ benötigt explizite Flags für BroadcastReceiver
        ContextCompat.registerReceiver(
            context,
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun installApk(file: File) {
        if (!file.exists()) {
            Log.e(tag, "APK-Datei nicht gefunden: ${file.absolutePath}")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Installation fehlgeschlagen", e)
            Toast.makeText(context, context.getString(R.string.settings_update_install_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }
}
