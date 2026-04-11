// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules reminder alarms via [AlarmManager].
 *
 * Uses [AlarmManager.setAlarmClock] because it is the only public API that
 * guarantees exact firing without requiring `SCHEDULE_EXACT_ALARM` permission
 * and without being batched/delayed by Doze or app standby. The downside is
 * that Android shows a "next alarm" indicator in the status bar / lockscreen,
 * which is arguably the right UX for user-scheduled reminders anyway.
 *
 * Previously used `setAndAllowWhileIdle` but that drifted several minutes for
 * short delays and occasionally dropped 20-second reminders entirely when the
 * IME process was swapped out.
 */
object ReminderScheduler {

    const val CHANNEL_ID = "deskdrop_reminders"
    const val EXTRA_REMINDER_ID = "reminder_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            // Migrate: if the channel was created with IMPORTANCE_DEFAULT (old code),
            // delete and recreate with IMPORTANCE_HIGH so reminders are prominent.
            // Android doesn't allow programmatically raising importance after creation.
            if (existing.importance < NotificationManager.IMPORTANCE_HIGH) {
                nm.deleteNotificationChannel(CHANNEL_ID)
                Log.i("ReminderScheduler", "Deleted old reminder channel (importance=${existing.importance}), will recreate with HIGH")
            } else {
                return
            }
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Deskdrop AI reminders"
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun alarmPendingIntent(context: Context, reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "helium314.keyboard.latin.ai.REMINDER_FIRE"
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            flags
        )
    }

    /**
     * Schedule an alarm. Tries, in order:
     *  1. [AlarmManager.setAlarmClock] — exact, bypasses Doze, no permission
     *     needed in theory. Some OEMs still block it.
     *  2. [AlarmManager.setExactAndAllowWhileIdle] — exact but only works if
     *     the app has `SCHEDULE_EXACT_ALARM` granted (we don't declare it).
     *  3. [AlarmManager.setAndAllowWhileIdle] — inexact, drifts a few minutes
     *     in Doze but always works.
     *  4. [AlarmManager.set] — last-resort legacy fallback.
     *
     * Only commits the reminder to [ReminderStore] after a successful schedule,
     * so failed attempts don't leave ghost reminders behind.
     *
     * Throws if none of the fallbacks worked — the caller turns that into a
     * tool-result error the model can report back.
     */
    fun schedule(context: Context, reminder: ReminderStore.Reminder) {
        ensureChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: throw IllegalStateException("AlarmManager unavailable")
        val pi = alarmPendingIntent(context, reminder.id)

        val method = trySchedule(am, pi, reminder.fireAt)
            ?: throw IllegalStateException("No alarm method worked on this device")

        Log.i("ReminderScheduler", "Scheduled reminder ${reminder.id} via $method for ${reminder.fireAt}")
        ReminderStore.add(context, reminder)
    }

    private fun trySchedule(am: AlarmManager, pi: PendingIntent, fireAt: Long): String? {
        // 1. setAlarmClock — preferred, exact, Doze-exempt, no permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val info = AlarmManager.AlarmClockInfo(fireAt, pi)
                am.setAlarmClock(info, pi)
                return "setAlarmClock"
            } catch (e: SecurityException) {
                Log.w("ReminderScheduler", "setAlarmClock denied: ${e.message}")
            } catch (e: Exception) {
                Log.w("ReminderScheduler", "setAlarmClock failed: ${e.message}")
            }
        }

        // 2. setExactAndAllowWhileIdle — exact, works if SCHEDULE_EXACT_ALARM
        //    is granted (we don't declare it, but on pre-31 it's implicit).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.canScheduleExactAlarms()
                } else true
                if (canUseExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
                    return "setExactAndAllowWhileIdle"
                }
            } catch (e: SecurityException) {
                Log.w("ReminderScheduler", "setExactAndAllowWhileIdle denied: ${e.message}")
            } catch (e: Exception) {
                Log.w("ReminderScheduler", "setExactAndAllowWhileIdle failed: ${e.message}")
            }
        }

        // 3. setAndAllowWhileIdle — inexact but Doze-aware
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
                return "setAndAllowWhileIdle"
            } catch (e: Exception) {
                Log.w("ReminderScheduler", "setAndAllowWhileIdle failed: ${e.message}")
            }
        }

        // 4. Plain set — last resort
        try {
            am.set(AlarmManager.RTC_WAKEUP, fireAt, pi)
            return "set"
        } catch (e: Exception) {
            Log.e("ReminderScheduler", "All alarm methods failed", e)
        }

        return null
    }

    /**
     * Re-register all reminders that are still in the future, and mark
     * any reminders whose fire-time has passed as unread so the user
     * sees them in the keyboard toolbar (existing orange-tint flow).
     *
     * Called on [Intent.ACTION_BOOT_COMPLETED] and
     * [Intent.ACTION_MY_PACKAGE_REPLACED], because Android wipes
     * AlarmManager alarms on both events. Without this, any reminder
     * set before a reboot or app update would silently disappear.
     */
    fun rescheduleAll(context: Context) {
        ensureChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val now = System.currentTimeMillis()
        // Orphan sweep: any reminder whose chat no longer exists in the
        // conversation index is dropped. Otherwise a deleted chat leaves
        // its reminders behind forever, silently inflating the unread badge.
        val liveChatIds: Set<String> = try {
            ConversationStore.listAll(context).mapNotNull { it.id }.toSet()
        } catch (_: Exception) { emptySet() }
        var orphans = 0
        for (r in ReminderStore.all(context)) {
            val cid = r.chatId
            if (cid != null && liveChatIds.isNotEmpty() && cid !in liveChatIds) {
                cancel(context, r.id)
                orphans++
                Log.i("ReminderScheduler", "Dropped orphan reminder ${r.id} (chat $cid gone)")
            }
        }
        val all = ReminderStore.all(context)
        var rescheduled = 0
        var missed = 0
        for (r in all) {
            if (r.fireAt > now) {
                if (am != null) {
                    val pi = alarmPendingIntent(context, r.id)
                    val method = trySchedule(am, pi, r.fireAt)
                    if (method != null) {
                        rescheduled++
                        Log.i("ReminderScheduler", "Re-scheduled ${r.id} via $method for ${r.fireAt}")
                    } else {
                        Log.w("ReminderScheduler", "Failed to re-schedule ${r.id}")
                    }
                }
            } else if (!r.unread) {
                // Fire-time passed while the alarm was dead (boot/update
                // downtime). Mark as unread so the missed reminder surfaces
                // in the toolbar tint instead of vanishing silently.
                ReminderStore.markUnread(context, r.id)
                missed++
                Log.i("ReminderScheduler", "Marked missed reminder ${r.id} as unread")
            }
        }
        Log.i("ReminderScheduler", "rescheduleAll: total=${all.size} rescheduled=$rescheduled missed=$missed orphans=$orphans")
    }

    fun cancel(context: Context, reminderId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am != null) {
            val pi = alarmPendingIntent(context, reminderId)
            am.cancel(pi)
        }
        ReminderStore.remove(context, reminderId)
    }
}
