// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.ai.AiToolRegistry.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Read and write the device calendar.
 *
 * One tool with an `action` parameter ("list" or "add") so the LLM has a
 * single concept to reach for instead of juggling two similar tools. Uses
 * the standard [CalendarContract] provider; requires `READ_CALENDAR` for
 * listing and `WRITE_CALENDAR` for adding. Permissions must already be
 * granted by the user in system settings — the tool returns an actionable
 * error otherwise (same pattern as [SetReminderTool]).
 *
 * Events are always inserted into the first writable calendar on the
 * device. Most users have exactly one (their primary account). Power users
 * with multiple calendars can extend this later by surfacing a picker.
 */
class CalendarTool : AiTool {

    override val name = "calendar"

    override val description =
        "Read, add, update, or delete events in the user's device calendar. " +
        "Use action='list' with 'days_ahead' to list upcoming events (each line includes id=N), " +
        "action='add' with 'title', 'start' (ISO 8601, or relative: 'tomorrow', 'morgen', 'overmorgen', 'next tuesday', 'volgende week dinsdag', optionally with time like 'tomorrow 15:00'), and optional 'end', 'location', 'description', 'all_day', " +
        "action='update' with 'event_id' plus any fields to change, " +
        "or action='delete' with 'event_id' to remove an event. " +
        "IMPORTANT: update and delete require TWO calls. First call WITHOUT confirm to preview the change, " +
        "then show the preview to the user and ask for confirmation, then call again WITH confirm=true. " +
        "NEVER guess an event_id — always run action='list' first to get real ids. " +
        "Times are interpreted in the device's local timezone unless they include an explicit offset."

    override val gate = ToolGate.ACTIONS

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().put("list").put("add").put("update").put("delete"))
                put("description", "'list' to read upcoming events, 'add' to create, 'update' to change, 'delete' to remove.")
            })
            put("event_id", JSONObject().apply {
                put("type", JSONArray().put("number").put("string"))
                put("description", "For action='update' or 'delete': numeric id of the event (obtain via action='list').")
            })
            // list params
            put("days_ahead", JSONObject().apply {
                put("type", JSONArray().put("number").put("string"))
                put("description", "For action='list': how many days into the future to look (default 7, max 90).")
            })
            put("max_results", JSONObject().apply {
                put("type", JSONArray().put("number").put("string"))
                put("description", "For action='list': maximum number of events to return (default 20, max 50).")
            })
            // add params
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "For action='add': event title.")
            })
            put("start", JSONObject().apply {
                put("type", "string")
                put("description", "For action='add': ISO 8601 start datetime (e.g. '2026-04-10T15:30').")
            })
            put("end", JSONObject().apply {
                put("type", "string")
                put("description", "For action='add': ISO 8601 end datetime. Defaults to start + 1 hour.")
            })
            put("location", JSONObject().apply {
                put("type", "string")
                put("description", "For action='add': optional event location.")
            })
            put("description", JSONObject().apply {
                put("type", "string")
                put("description", "For action='add': optional longer description.")
            })
            put("all_day", JSONObject().apply {
                put("type", "boolean")
                put("description", "For action='add': true for an all-day event (ignores time-of-day).")
            })
            put("confirm", JSONObject().apply {
                put("type", "boolean")
                put("description", "For action='update' or 'delete': must be true to execute. " +
                    "First call without confirm to preview what will change, then ask the user for confirmation, " +
                    "then call again with confirm=true to execute.")
            })
        })
        put("required", JSONArray().put("action"))
    }

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> listEvents(args, ctx)
            "add" -> addEvent(args, ctx)
            "update" -> updateEvent(args, ctx)
            "delete" -> deleteEvent(args, ctx)
            else -> ToolResult(
                "Invalid action '$action'. Must be 'list', 'add', 'update', or 'delete'.",
                isError = true
            )
        }
    }

    private fun openAppSettings(ctx: ToolContext, message: String): ToolResult {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.appContext.startActivity(intent)
        } catch (_: Exception) { }
        return ToolResult(message, isError = true)
    }

    // ────────────────────────────────────────────────────────────────────
    // list
    // ────────────────────────────────────────────────────────────────────

    private fun listEvents(args: JSONObject, ctx: ToolContext): ToolResult {
        val granted = ContextCompat.checkSelfPermission(
            ctx.appContext, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return openAppSettings(ctx, "Calendar permission not granted. I've opened settings for you. Enable 'Calendar' and try again.")
        }

        val daysAhead = args.optLong("days_ahead", 7L).coerceIn(1L, 90L)
        val maxResults = args.optLong("max_results", 20L).coerceIn(1L, 50L).toInt()

        val now = System.currentTimeMillis()
        val endWindow = now + daysAhead * 24L * 3600L * 1000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(endWindow.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )

        val events = mutableListOf<String>()
        try {
            ctx.appContext.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val humanFmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
                    .apply { timeZone = TimeZone.getDefault() }
                val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
                    .apply { timeZone = TimeZone.getDefault() }
                var taken = 0
                while (cursor.moveToNext() && taken < maxResults) {
                    val eventId = cursor.getLong(0)
                    val title = cursor.getString(1) ?: "(untitled)"
                    val begin = cursor.getLong(2)
                    val end = cursor.getLong(3)
                    val allDay = cursor.getInt(4) == 1
                    val location = cursor.getString(5) ?: ""
                    val calName = cursor.getString(6) ?: ""
                    val when_ = if (allDay) {
                        dayFmt.format(Date(begin)) + " (all day)"
                    } else {
                        humanFmt.format(Date(begin)) + "–" +
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end))
                    }
                    val parts = mutableListOf("• [id=$eventId] $when_ — $title")
                    if (location.isNotBlank()) parts.add("    @ $location")
                    if (calName.isNotBlank()) parts.add("    [$calName]")
                    events.add(parts.joinToString("\n"))
                    taken++
                }
            }
        } catch (e: SecurityException) {
            return ToolResult("Calendar permission denied: ${e.message}", isError = true)
        } catch (e: Exception) {
            Log.e("CalendarTool", "list failed", e)
            return ToolResult("Failed to read calendar: ${e.message}", isError = true)
        }

        return if (events.isEmpty()) {
            ToolResult("No events in the next $daysAhead days.")
        } else {
            ToolResult(
                "Upcoming events (next $daysAhead days):\n" + events.joinToString("\n")
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // add
    // ────────────────────────────────────────────────────────────────────

    private fun addEvent(args: JSONObject, ctx: ToolContext): ToolResult {
        val granted = ContextCompat.checkSelfPermission(
            ctx.appContext, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return openAppSettings(ctx, "Calendar permission not granted. I've opened settings for you. Enable 'Calendar' and try again.")
        }

        val title = args.optString("title").trim()
        if (title.isBlank()) {
            return ToolResult("Missing required 'title' for action='add'.", isError = true)
        }
        val startRaw = args.optString("start").trim()
        if (startRaw.isBlank()) {
            return ToolResult("Missing required 'start' (ISO 8601 datetime) for action='add'.", isError = true)
        }
        val startMs = parseIsoDateTime(startRaw)
            ?: return ToolResult("Could not parse start '$startRaw' (expected ISO 8601).", isError = true)

        val allDay = args.optBoolean("all_day", false)
        val endRaw = args.optString("end").trim()
        val endMs = if (endRaw.isNotBlank()) {
            parseIsoDateTime(endRaw)
                ?: return ToolResult("Could not parse end '$endRaw' (expected ISO 8601).", isError = true)
        } else {
            // Default: 1h event, or full day if all_day
            if (allDay) startMs + 24L * 3600L * 1000L
            else startMs + 3600L * 1000L
        }
        if (endMs <= startMs) {
            return ToolResult("End time must be after start time.", isError = true)
        }

        val location = args.optString("location").trim().ifBlank { null }
        val description = args.optString("description").trim().ifBlank { null }

        // Find the best writable calendar.
        val chosen = findWritableCalendar(ctx)
            ?: return ToolResult(
                "No writable calendar found on this device. Make sure at least one account has a calendar enabled.",
                isError = true
            )
        val calendarId = chosen.first
        val calendarName = chosen.second

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
        }

        return try {
            val uri = ctx.appContext.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI, values
            ) ?: return ToolResult("Calendar insert returned null.", isError = true)
            val id = ContentUris.parseId(uri)
            val human = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
                .apply { timeZone = TimeZone.getDefault() }
                .format(Date(startMs))
            ToolResult("Added '$title' on $human to calendar '$calendarName' (id=$id).")
        } catch (e: SecurityException) {
            ToolResult("Calendar permission denied: ${e.message}", isError = true)
        } catch (e: Exception) {
            Log.e("CalendarTool", "add failed", e)
            ToolResult("Failed to add event: ${e.message}", isError = true)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // delete
    // ────────────────────────────────────────────────────────────────────

    private fun deleteEvent(args: JSONObject, ctx: ToolContext): ToolResult {
        val granted = ContextCompat.checkSelfPermission(
            ctx.appContext, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return openAppSettings(ctx, "Calendar permission not granted. I've opened settings for you. Enable 'Calendar' and try again.")
        }
        val eventId = args.optLong("event_id", -1L)
        if (eventId <= 0L) {
            return ToolResult(
                "Missing or invalid 'event_id' for action='delete'. Run action='list' first to get real ids.",
                isError = true
            )
        }

        val lookup = lookupEvent(ctx, eventId)
            ?: return ToolResult(
                "No event with id=$eventId found. Run action='list' to see current ids.",
                isError = true
            )
        val now = System.currentTimeMillis()
        if (lookup.endMs > 0 && lookup.endMs < now - 5L * 60L * 1000L) {
            return ToolResult(
                "Refusing to delete past event '${lookup.title}' (id=$eventId). Only current or future events can be deleted via this tool.",
                isError = true
            )
        }

        // Require explicit confirmation before destructive action
        val confirmed = args.optBoolean("confirm", false)
        if (!confirmed) {
            val human = if (lookup.startMs > 0) {
                " on " + SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
                    .apply { timeZone = TimeZone.getDefault() }
                    .format(Date(lookup.startMs))
            } else ""
            return ToolResult(
                "About to DELETE '${lookup.title}'$human (id=$eventId). " +
                "Ask the user to confirm, then call this tool again with confirm=true to execute."
            )
        }

        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = ctx.appContext.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                val human = if (lookup.startMs > 0) {
                    " on " + SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
                        .apply { timeZone = TimeZone.getDefault() }
                        .format(Date(lookup.startMs))
                } else ""
                ToolResult("Deleted '${lookup.title}'$human (id=$eventId).")
            } else {
                ToolResult("No rows deleted — event id=$eventId may not exist.", isError = true)
            }
        } catch (e: SecurityException) {
            ToolResult("Calendar permission denied: ${e.message}", isError = true)
        } catch (e: Exception) {
            Log.e("CalendarTool", "delete failed", e)
            ToolResult("Failed to delete event: ${e.message}", isError = true)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // update
    // ────────────────────────────────────────────────────────────────────

    private fun updateEvent(args: JSONObject, ctx: ToolContext): ToolResult {
        val granted = ContextCompat.checkSelfPermission(
            ctx.appContext, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return openAppSettings(ctx, "Calendar permission not granted. I've opened settings for you. Enable 'Calendar' and try again.")
        }
        val eventId = args.optLong("event_id", -1L)
        if (eventId <= 0L) {
            return ToolResult(
                "Missing or invalid 'event_id' for action='update'. Run action='list' first to get real ids.",
                isError = true
            )
        }
        val existing = lookupEvent(ctx, eventId)
            ?: return ToolResult(
                "No event with id=$eventId found. Run action='list' to see current ids.",
                isError = true
            )

        val values = ContentValues()
        val changed = mutableListOf<String>()

        val newTitle = args.optString("title").trim()
        if (newTitle.isNotBlank()) {
            values.put(CalendarContract.Events.TITLE, newTitle)
            changed.add("title")
        }
        val newStartRaw = args.optString("start").trim()
        val newStartMs = if (newStartRaw.isNotBlank()) {
            parseIsoDateTime(newStartRaw)
                ?: return ToolResult("Could not parse start '$newStartRaw' (expected ISO 8601).", isError = true)
        } else null
        val newEndRaw = args.optString("end").trim()
        val newEndMs = if (newEndRaw.isNotBlank()) {
            parseIsoDateTime(newEndRaw)
                ?: return ToolResult("Could not parse end '$newEndRaw' (expected ISO 8601).", isError = true)
        } else null

        if (newStartMs != null) {
            values.put(CalendarContract.Events.DTSTART, newStartMs)
            changed.add("start")
            // If end not supplied but start moved, shift end by same delta to keep duration.
            if (newEndMs == null && existing.startMs > 0 && existing.endMs > 0) {
                val delta = newStartMs - existing.startMs
                values.put(CalendarContract.Events.DTEND, existing.endMs + delta)
            }
        }
        if (newEndMs != null) {
            values.put(CalendarContract.Events.DTEND, newEndMs)
            changed.add("end")
        }
        if (args.has("location")) {
            values.put(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
            changed.add("location")
        }
        if (args.has("description")) {
            values.put(CalendarContract.Events.DESCRIPTION, args.optString("description"))
            changed.add("description")
        }
        if (args.has("all_day")) {
            values.put(CalendarContract.Events.ALL_DAY, if (args.optBoolean("all_day")) 1 else 0)
            changed.add("all_day")
        }

        if (values.size() == 0) {
            return ToolResult(
                "No fields to update for event id=$eventId. Provide title/start/end/location/description/all_day.",
                isError = true
            )
        }

        // Require explicit confirmation before destructive action
        val confirmed = args.optBoolean("confirm", false)
        if (!confirmed) {
            return ToolResult(
                "About to UPDATE '${existing.title}' (id=$eventId): change ${changed.joinToString(", ")}. " +
                "Ask the user to confirm, then call this tool again with confirm=true to execute."
            )
        }

        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = ctx.appContext.contentResolver.update(uri, values, null, null)
            if (rows > 0) {
                ToolResult("Updated '${existing.title}' (id=$eventId): ${changed.joinToString(", ")}.")
            } else {
                ToolResult("No rows updated — event id=$eventId may not exist.", isError = true)
            }
        } catch (e: SecurityException) {
            ToolResult("Calendar permission denied: ${e.message}", isError = true)
        } catch (e: Exception) {
            Log.e("CalendarTool", "update failed", e)
            ToolResult("Failed to update event: ${e.message}", isError = true)
        }
    }

    private data class EventSnapshot(val title: String, val startMs: Long, val endMs: Long)

    private fun lookupEvent(ctx: ToolContext, eventId: Long): EventSnapshot? {
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            ctx.appContext.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    EventSnapshot(
                        title = cursor.getString(0) ?: "(untitled)",
                        startMs = cursor.getLong(1),
                        endMs = cursor.getLong(2)
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.w("CalendarTool", "lookupEvent($eventId) failed: ${e.message}")
            null
        }
    }

    /**
     * Pick the best writable calendar on the device.
     *
     * Most Android devices have multiple calendars (LOCAL stub, Google primary,
     * holidays, birthdays, shared calendars, …). Naively picking "first writable
     * sorted by _ID" lands in an invisible LOCAL stub and the event disappears
     * from the user's view. We score candidates so that the calendar the user
     * actually looks at wins:
     *   - must be writable (CAL_ACCESS_CONTRIBUTOR+) and visible
     *   - prefer non-LOCAL account types (real Google/Exchange accounts)
     *   - prefer IS_PRIMARY
     *   - prefer SYNC_EVENTS=1
     *   - tiebreak on lowest _ID
     *
     * Returns (id, displayName) so the caller can echo the chosen calendar back
     * to the user — essential for debugging when something lands in the wrong
     * place.
     */
    private fun findWritableCalendar(ctx: ToolContext): Pair<Long, String>? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,                         // 0
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,       // 1
            CalendarContract.Calendars.ACCOUNT_TYPE,                // 2
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,       // 3
            CalendarContract.Calendars.VISIBLE,                     // 4
            CalendarContract.Calendars.IS_PRIMARY,                  // 5
            CalendarContract.Calendars.SYNC_EVENTS                  // 6
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        )
        data class Cal(
            val id: Long, val name: String, val accountType: String,
            val visible: Boolean, val primary: Boolean, val sync: Boolean
        )
        val candidates = mutableListOf<Cal>()
        try {
            ctx.appContext.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    candidates.add(
                        Cal(
                            id = cursor.getLong(0),
                            name = cursor.getString(1) ?: "(unnamed)",
                            accountType = cursor.getString(2) ?: "",
                            visible = cursor.getInt(4) == 1,
                            primary = cursor.getInt(5) == 1,
                            sync = cursor.getInt(6) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("CalendarTool", "findWritableCalendar failed: ${e.message}")
            return null
        }
        if (candidates.isEmpty()) return null

        // Score: higher is better.
        fun score(c: Cal): Int {
            var s = 0
            if (c.visible) s += 1000
            if (c.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL) s += 500
            if (c.primary) s += 200
            if (c.sync) s += 100
            return s
        }
        val best = candidates.maxWithOrNull(
            compareBy<Cal> { score(it) }.thenByDescending { -it.id }
        ) ?: return null
        Log.i(
            "CalendarTool",
            "chose calendar id=${best.id} name='${best.name}' type=${best.accountType} " +
                "visible=${best.visible} primary=${best.primary} sync=${best.sync} " +
                "(from ${candidates.size} writable)"
        )
        return best.id to best.name
    }

    private fun parseIsoDateTime(raw: String): Long? {
        // Handle relative date words (models often pass these instead of ISO dates)
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        val relativeBase = resolveRelativeDate(trimmed)
        if (relativeBase != null) return relativeBase

        // Handle "tomorrow 15:00" or "morgen 09:30" style
        val relativeWithTime = resolveRelativeDateWithTime(trimmed)
        if (relativeWithTime != null) return relativeWithTime

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mmXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.ROOT)
                if (!p.contains("XXX")) sdf.timeZone = TimeZone.getDefault()
                sdf.isLenient = false
                return sdf.parse(raw)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    /**
     * Resolve relative date expressions to epoch millis (default 09:00).
     * Supports EN + NL: today, tomorrow, overmorgen, next monday, volgende week dinsdag, etc.
     */
    private fun resolveRelativeDate(input: String): Long? {
        val daysOffset = resolveRelativeDaysOffset(input) ?: return null
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, daysOffset)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Resolve "tomorrow 15:00", "next friday 09:30", "volgende week dinsdag 14:00" etc. */
    private fun resolveRelativeDateWithTime(input: String): Long? {
        // Try to split off a trailing HH:mm time
        val timeMatch = Regex("(\\d{1,2}):(\\d{2})$").find(input) ?: return null
        val datePart = input.substring(0, timeMatch.range.first).trim()
        if (datePart.isEmpty()) return null
        val daysOffset = resolveRelativeDaysOffset(datePart) ?: return null
        val hour = timeMatch.groupValues[1].toInt()
        val minute = timeMatch.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null

        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, daysOffset)
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Map a relative date phrase to a day offset from today. */
    private fun resolveRelativeDaysOffset(input: String): Int? {
        // Simple keywords
        val simple = when (input) {
            "today", "vandaag" -> 0
            "tomorrow", "morgen" -> 1
            "day after tomorrow", "overmorgen" -> 2
            "yesterday", "gisteren" -> -1
            else -> null
        }
        if (simple != null) return simple

        // "next <weekday>" / "volgende <weekday>" / "volgende week <weekday>"
        // "this <weekday>" / "deze <weekday>"
        // "komende <weekday>"
        val nextDayMatch = Regex(
            "^(?:next|volgende(?:\\s+week)?|komende|deze|this)\\s+(.+)$"
        ).matchEntire(input) ?: return null
        val dayName = nextDayMatch.groupValues[1].trim()
        val targetDow = parseDayOfWeek(dayName) ?: return null

        val today = java.util.Calendar.getInstance()
        val todayDow = today.get(java.util.Calendar.DAY_OF_WEEK)

        // "next X" / "volgende X" = the coming occurrence, at least 1 day away
        // "volgende week X" = always next week (at least 2 days for same weekday)
        val isNextWeek = input.contains("volgende week") || input.contains("next week")
        var diff = targetDow - todayDow
        if (diff <= 0) diff += 7
        // "volgende week" means skip this week's occurrence
        if (isNextWeek && diff < 7) diff += 7
        // For "next friday" when today is friday, go to next week
        if (diff == 0) diff = 7

        return diff
    }

    /** Parse a weekday name (EN/NL) to Calendar.DAY_OF_WEEK constant. */
    private fun parseDayOfWeek(name: String): Int? {
        return when (name) {
            "monday", "maandag", "mon", "ma" -> java.util.Calendar.MONDAY
            "tuesday", "dinsdag", "tue", "di" -> java.util.Calendar.TUESDAY
            "wednesday", "woensdag", "wed", "wo" -> java.util.Calendar.WEDNESDAY
            "thursday", "donderdag", "thu", "do" -> java.util.Calendar.THURSDAY
            "friday", "vrijdag", "fri", "vr" -> java.util.Calendar.FRIDAY
            "saturday", "zaterdag", "sat", "za" -> java.util.Calendar.SATURDAY
            "sunday", "zondag", "sun", "zo" -> java.util.Calendar.SUNDAY
            else -> null
        }
    }
}
