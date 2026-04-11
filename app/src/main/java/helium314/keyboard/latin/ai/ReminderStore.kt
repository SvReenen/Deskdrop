// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * On-disk persistence for reminders. Simple JSON file in no-backup storage:
 * ```
 * reminders.json  →  [{id, fireAt, message, chatId?, unread}, …]
 * ```
 *
 * Unread flag is flipped to true by [ReminderReceiver] when the alarm fires,
 * and cleared in bulk by [ConversationActivity] on open. The toolbar reads
 * [hasUnread] to decide whether to tint the AI_CONVERSATION button.
 */
object ReminderStore {

    data class Reminder(
        val id: String,
        val fireAt: Long,
        val message: String,
        val chatId: String?,
        val unread: Boolean
    )

    private const val FILE_NAME = "reminders.json"

    fun newId(): String = UUID.randomUUID().toString()

    private fun file(context: Context): File =
        File(context.noBackupFilesDir, FILE_NAME)

    @Synchronized
    fun all(context: Context): List<Reminder> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Reminder>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(
                    Reminder(
                        id = id,
                        fireAt = o.optLong("fireAt", 0L),
                        message = o.optString("message"),
                        chatId = o.optString("chatId").takeIf { it.isNotBlank() },
                        unread = o.optBoolean("unread", false)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun writeAll(context: Context, list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("fireAt", r.fireAt)
                put("message", r.message)
                if (r.chatId != null) put("chatId", r.chatId)
                put("unread", r.unread)
            })
        }
        val target = file(context)
        val tmp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString())
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    @Synchronized
    fun add(context: Context, r: Reminder) {
        val list = all(context).toMutableList()
        list.removeAll { it.id == r.id }
        list.add(r)
        writeAll(context, list)
    }

    @Synchronized
    fun get(context: Context, id: String): Reminder? =
        all(context).firstOrNull { it.id == id }

    @Synchronized
    fun remove(context: Context, id: String) {
        val list = all(context).toMutableList()
        if (list.removeAll { it.id == id }) {
            writeAll(context, list)
        }
    }

    @Synchronized
    fun markUnread(context: Context, id: String) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(unread = true)
        writeAll(context, list)
    }

    /**
     * Acknowledge all currently-unread reminders by deleting them outright.
     *
     * Previously this just flipped `unread=false`, but that left the row on
     * disk with `fireAt` in the past. On the next boot/package-replace,
     * [ReminderScheduler.rescheduleAll] would see `fireAt<now && !unread`,
     * interpret it as "missed while device was off", and re-mark it as
     * unread — so cleared reminders kept reappearing after every reinstall.
     *
     * There is no history view for acknowledged reminders, so deleting is
     * lossless from the user's perspective and makes the state truly sticky.
     * Future-scheduled reminders (which are `unread=false` by default) are
     * never touched here because of the `it.unread` filter, so their alarms
     * keep firing.
     */
    @Synchronized
    fun markAllRead(context: Context) {
        val list = all(context)
        if (list.none { it.unread }) return
        writeAll(context, list.filterNot { it.unread })
    }

    /**
     * Delete every unread reminder tied to [chatId]. Pass null to clear
     * reminders that were created outside a conversation context. See
     * [markAllRead] for why this deletes instead of flipping a flag.
     */
    @Synchronized
    fun markReadForChat(context: Context, chatId: String?) {
        val list = all(context)
        if (list.none { it.unread && it.chatId == chatId }) return
        writeAll(context, list.filterNot { it.unread && it.chatId == chatId })
    }

    @Synchronized
    fun hasUnread(context: Context): Boolean =
        try { all(context).any { it.unread } } catch (_: Exception) { false }

    /**
     * Set of chat IDs that have at least one unread reminder. Reminders
     * with a null chatId are excluded — callers wanting those should use
     * [hasUnreadUnscoped].
     */
    @Synchronized
    fun unreadChatIds(context: Context): Set<String> =
        try {
            all(context).asSequence()
                .filter { it.unread && it.chatId != null }
                .mapNotNull { it.chatId }
                .toSet()
        } catch (_: Exception) { emptySet() }

    /** True if any unread reminder exists with a null chatId (outside-chat reminder). */
    @Synchronized
    fun hasUnreadUnscoped(context: Context): Boolean =
        try { all(context).any { it.unread && it.chatId == null } } catch (_: Exception) { false }
}
