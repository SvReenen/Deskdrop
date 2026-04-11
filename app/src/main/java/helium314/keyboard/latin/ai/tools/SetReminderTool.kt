// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.ai.AiToolRegistry.ToolResult
import helium314.keyboard.latin.ai.ReminderScheduler
import helium314.keyboard.latin.ai.ReminderStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Schedule a reminder tied to the current conversation. First tool built on
 * the new [AiTool] interface — see that file for the pattern.
 */
class SetReminderTool : AiTool {

    override val name = "set_reminder"

    override val description =
        "Schedule a reminder. The user will receive a system notification at the chosen time; " +
        "tapping it reopens this exact conversation with the reminder appended. " +
        "Use `seconds_from_now` for relative times (e.g. 'in 10 minutes') or `datetime` " +
        "(ISO 8601, e.g. '2026-04-09T09:30:00') for absolute times. At least one of the two " +
        "must be provided. Keep `message` short (a few words)."

    override val gate = ToolGate.ACTIONS

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("message", JSONObject().apply {
                put("type", "string")
                put("description", "Short human-readable reminder text, e.g. 'call the plumber'.")
            })
            put("seconds_from_now", JSONObject().apply {
                // Accept integer, float, or string — Groq rejects the tool call
                // if the model emits 60.0 or "60" while type is strictly 'integer'.
                put("type", JSONArray().put("number").put("string"))
                put("description", "Relative delay in seconds. Optional if 'datetime' is given.")
            })
            put("datetime", JSONObject().apply {
                put("type", "string")
                put("description", "Absolute fire time in ISO 8601 (local timezone if no offset). Optional if 'seconds_from_now' is given.")
            })
        })
        put("required", JSONArray().put("message"))
    }

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        // Resolve 'message' tolerantly — some LLMs send it as 'text' or 'content'.
        val message = (
            args.optString("message").ifBlank {
                args.optString("text").ifBlank {
                    args.optString("content").ifBlank {
                        args.optString("body")
                    }
                }
            }
        ).trim()
        if (message.isBlank()) {
            return ToolResult(
                "Missing required parameter 'message'. Received keys: ${args.keys().asSequence().toList()}",
                isError = true
            )
        }

        val now = System.currentTimeMillis()
        val oneYearMs = 365L * 24 * 3600 * 1000

        // Be tolerant about the time parameter — Gemini/Ollama tool calling
        // occasionally sends typos or alternate names. Accept a shortlist of
        // known aliases plus a generic "contains 'second'" fallback.
        val secondsValue = extractSecondsFromNow(args)
        val datetimeValue = extractDatetime(args)

        val fireAt: Long = when {
            secondsValue != null -> {
                if (secondsValue <= 0) {
                    return ToolResult("Seconds value must be positive, got $secondsValue", isError = true)
                }
                now + secondsValue * 1000
            }
            datetimeValue != null -> {
                parseIsoDateTime(datetimeValue)
                    ?: return ToolResult(
                        "Could not parse datetime '$datetimeValue' (expected ISO 8601 like 2026-04-09T09:30:00)",
                        isError = true
                    )
            }
            else -> return ToolResult(
                "Provide either 'seconds_from_now' (integer) or 'datetime' (ISO 8601 string). " +
                "Received keys: ${args.keys().asSequence().toList()}. " +
                "Retry the tool call with the correct parameter name.",
                isError = true
            )
        }

        if (fireAt <= now + 500) {
            return ToolResult("Reminder time is in the past", isError = true)
        }
        if (fireAt - now > oneYearMs) {
            return ToolResult("Reminder more than one year in the future is not supported", isError = true)
        }

        // POST_NOTIFICATIONS check on Android 13+. No runtime prompt — the user
        // must grant it via system settings in this iteration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx.appContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.appContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.appContext.startActivity(intent)
                } catch (_: Exception) { }
                return ToolResult(
                    "Notification permission not granted. I've opened settings for you. Enable 'Notifications' and try again.",
                    isError = true
                )
            }
        }

        val reminder = ReminderStore.Reminder(
            id = ReminderStore.newId(),
            fireAt = fireAt,
            message = message,
            chatId = ctx.chatId,
            unread = false
        )

        return try {
            ReminderScheduler.schedule(ctx.appContext, reminder)
            val human = java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
                .apply { timeZone = TimeZone.getDefault() }
                .format(java.util.Date(fireAt))
            ToolResult("Reminder set for $human: $message")
        } catch (e: Exception) {
            Log.e("SetReminderTool", "Failed to schedule reminder", e)
            ToolResult("Failed to schedule reminder: ${e.message}", isError = true)
        }
    }

    /**
     * Try to extract a "delay in seconds" value from the args, accepting:
     *  - the canonical `seconds_from_now`
     *  - common aliases: `seconds`, `delay_seconds`, `secondsFromNow`, `delay`, `in_seconds`
     *  - a few convenience unit forms: `minutes_from_now` / `minutes`, `hours_from_now` / `hours`
     *  - any key containing "second" with a numeric value (fallback for typos)
     * Returns null if nothing matches.
     */
    private fun extractSecondsFromNow(args: JSONObject): Long? {
        val secondsAliases = listOf(
            "seconds_from_now", "secondsFromNow", "seconds", "delay_seconds",
            "delaySeconds", "delay", "in_seconds", "inSeconds"
        )
        for (k in secondsAliases) {
            if (args.has(k) && !args.isNull(k)) {
                val v = args.optLong(k, -1L)
                if (v > 0) return v
            }
        }
        val minuteAliases = listOf(
            "minutes_from_now", "minutesFromNow", "minutes", "delay_minutes", "in_minutes"
        )
        for (k in minuteAliases) {
            if (args.has(k) && !args.isNull(k)) {
                val v = args.optLong(k, -1L)
                if (v > 0) return v * 60
            }
        }
        val hourAliases = listOf(
            "hours_from_now", "hoursFromNow", "hours", "delay_hours", "in_hours"
        )
        for (k in hourAliases) {
            if (args.has(k) && !args.isNull(k)) {
                val v = args.optLong(k, -1L)
                if (v > 0) return v * 3600
            }
        }
        // Fallback: any key containing "second" with a positive integer value.
        val iter = args.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k.contains("second", ignoreCase = true)) {
                val v = args.optLong(k, -1L)
                if (v > 0) return v
            }
        }
        return null
    }

    /**
     * Extract an ISO-8601 datetime string from any of the common aliases.
     */
    private fun extractDatetime(args: JSONObject): String? {
        val aliases = listOf(
            "datetime", "date_time", "dateTime", "when", "time", "at", "fire_at", "fireAt"
        )
        for (k in aliases) {
            if (args.has(k) && !args.isNull(k)) {
                val v = args.optString(k).trim()
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    /**
     * Parse an ISO 8601 datetime string into epoch millis. Accepts forms with
     * or without timezone offset. If no offset is present the local timezone
     * is used. Returns null on failure.
     */
    private fun parseIsoDateTime(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mmXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm"
        )
        for (p in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(p, Locale.ROOT)
                if (!p.contains("XXX")) sdf.timeZone = TimeZone.getDefault()
                sdf.isLenient = false
                return sdf.parse(raw)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }
}
