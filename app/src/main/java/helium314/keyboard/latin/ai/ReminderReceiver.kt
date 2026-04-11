// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import helium314.keyboard.latin.R

/**
 * Fired by [android.app.AlarmManager] when a reminder is due. Marks the
 * reminder as unread in the store and posts a notification whose tap
 * intent reopens [ConversationActivity] with the originating chat and
 * the reminder id.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val reminder = ReminderStore.get(context, reminderId) ?: return
        Log.i(TAG, "Reminder fired: id=$reminderId")

        ReminderStore.markUnread(context, reminderId)
        ReminderScheduler.ensureChannel(context)

        // Verify channel exists and has sufficient importance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val ch = nm?.getNotificationChannel(ReminderScheduler.CHANNEL_ID)
            Log.i(TAG, "Channel ${ReminderScheduler.CHANNEL_ID}: importance=${ch?.importance} (need >= ${NotificationManager.IMPORTANCE_DEFAULT})")
        }

        val tapIntent = Intent(context, ConversationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ConversationActivity.EXTRA_CHAT_ID, reminder.chatId)
            putExtra(ConversationActivity.EXTRA_REMINDER_ID, reminderId)
        }
        var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
        }
        val tapPending = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            tapIntent,
            piFlags
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setContentTitle("\u23F0 Reminder")
            .setContentText(reminder.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(reminderId.hashCode(), notification)
            Log.i(TAG, "Notification posted for reminder $reminderId")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
    }
}
