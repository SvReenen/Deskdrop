package helium314.keyboard.latin.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version of Deskdrop and shows a notification.
 * Users can tap "Update" to download, "Later" to be reminded tomorrow,
 * or "Skip" to permanently ignore that version.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "SvReenen/Deskdrop"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHANNEL_ID = "deskdrop_update"
    private const val NOTIFICATION_ID = 9001
    private const val PREFS_NAME = "deskdrop_update"
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val PREF_SKIPPED_VERSION = "update_skipped_version"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // once per day

    private const val ACTION_SKIP = "helium314.keyboard.UPDATE_SKIP"
    private const val ACTION_LATER = "helium314.keyboard.UPDATE_LATER"
    private const val EXTRA_VERSION = "version"

    fun checkInBackground(context: Context) {
        val appPrefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(context)
        if (!appPrefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_AUTO_UPDATE_CHECK, true)) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return

        Thread {
            try {
                check(context)
                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Manual check triggered by the user. Ignores the 24h interval and skipped version.
     * Returns a callback via [onResult]: true if update found, false if up to date, null on error.
     */
    data class UpdateResult(val found: Boolean?, val version: String? = null, val downloadUrl: String? = null)

    fun checkNow(context: Context, onResult: (UpdateResult) -> Unit) {
        Thread {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                try {
                    if (conn.responseCode != 200) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(UpdateResult(null)) }
                        return@Thread
                    }
                    val json = conn.inputStream.bufferedReader().readText()
                    val obj = org.json.JSONObject(json)
                    val tagName = obj.optString("tag_name", "").removePrefix("v")
                    val htmlUrl = obj.optString("html_url", "")
                    val currentVersion = getCurrentVersion(context)

                    if (tagName.isEmpty() || currentVersion == null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(UpdateResult(null)) }
                        return@Thread
                    }

                    if (isNewer(tagName, currentVersion)) {
                        val downloadUrl = findApkUrl(obj) ?: htmlUrl
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onResult(UpdateResult(true, tagName, downloadUrl))
                        }
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(UpdateResult(false)) }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Manual update check failed: ${e.message}")
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(UpdateResult(null)) }
            }
        }.start()
    }

    private fun check(context: Context) {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return

            val json = conn.inputStream.bufferedReader().readText()
            val obj = org.json.JSONObject(json)
            val tagName = obj.optString("tag_name", "").removePrefix("v")
            val htmlUrl = obj.optString("html_url", "")

            if (tagName.isEmpty() || htmlUrl.isEmpty()) return

            val currentVersion = getCurrentVersion(context) ?: return

            // Check if user skipped this version
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val skippedVersion = prefs.getString(PREF_SKIPPED_VERSION, null)
            if (tagName == skippedVersion) return

            if (isNewer(tagName, currentVersion)) {
                val downloadUrl = findApkUrl(obj) ?: htmlUrl
                showNotification(context, tagName, downloadUrl)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun getCurrentVersion(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    private fun findApkUrl(releaseJson: org.json.JSONObject): String? {
        val assets = releaseJson.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk")) {
                return asset.optString("browser_download_url", null)
            }
        }
        return null
    }

    private fun showNotification(context: Context, version: String, url: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications when a new Deskdrop version is available"
            }
            nm.createNotificationChannel(channel)
        }

        // Update: opens download link
        val updateIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val updatePending = PendingIntent.getActivity(
            context, 0, updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Later: just dismisses the notification (will reappear tomorrow)
        val laterIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_LATER
        }
        val laterPending = PendingIntent.getBroadcast(
            context, 1, laterIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Skip: saves this version so it won't be shown again
        val skipIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_SKIP
            putExtra(EXTRA_VERSION, version)
        }
        val skipPending = PendingIntent.getBroadcast(
            context, 2, skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setContentTitle(context.getString(R.string.update_available_title, version))
            .setContentText(context.getString(R.string.update_available_tap))
            .setContentIntent(updatePending)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.update_later), laterPending)
            .addAction(0, context.getString(R.string.update_skip), skipPending)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    class UpdateActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)

            if (intent.action == ACTION_SKIP) {
                val version = intent.getStringExtra(EXTRA_VERSION) ?: return
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_SKIPPED_VERSION, version)
                    .apply()
            }
        }
    }
}
