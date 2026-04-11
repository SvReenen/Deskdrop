package helium314.keyboard.latin.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version of Deskdrop and shows a notification.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "SvReenen/Deskdrop"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHANNEL_ID = "deskdrop_update"
    private const val NOTIFICATION_ID = 9001
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // once per day

    fun checkInBackground(context: Context) {
        val prefs = context.getSharedPreferences("deskdrop_update", Context.MODE_PRIVATE)
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

            if (isNewer(tagName, currentVersion)) {
                // Find APK download URL from assets
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

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setContentTitle("Deskdrop $version available")
            .setContentText("Tap to download the update")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
