// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import java.io.File

/**
 * File-based IPC bridge for text insertion from the `:chat` process back to
 * the IME process.
 *
 * Before process isolation, [ConversationActivity] called
 * `LatinIME.setPendingInsert(text)` directly (same-process static field).
 * With `:chat` running in a separate OS process, that volatile field is
 * invisible from the IME process. This bridge writes pending text to a known
 * file in device-protected storage; the IME reads + deletes it on the next
 * `onStartInputView()`.
 *
 * Device-protected storage is used so the bridge works pre-FBE-unlock,
 * consistent with `LatinIME` being `directBootAware`.
 *
 * There is also a boolean flag for "reopen AI dialog" (used by
 * [ResultViewActivity]'s "New prompt" button).
 */
object PendingInsertBridge {

    private const val INSERT_FILE = "pending_insert.txt"
    private const val REOPEN_FILE = "pending_reopen_ai.flag"

    private fun dpeDir(context: Context): File =
        context.createDeviceProtectedStorageContext().filesDir

    // ── Insert text ─────────────────────────────────────────────────────

    @JvmStatic
    fun writeInsert(context: Context, text: String) {
        try {
            val dir = dpeDir(context)
            val tmp = File(dir, "$INSERT_FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, INSERT_FILE))
        } catch (_: Exception) { /* best-effort */ }
    }

    @JvmStatic
    fun consumeInsert(context: Context): String? {
        return try {
            val f = File(dpeDir(context), INSERT_FILE)
            if (!f.exists()) return null
            val text = f.readText()
            f.delete()
            text.ifBlank { null }
        } catch (_: Exception) { null }
    }

    // ── Reopen AI dialog flag ───────────────────────────────────────────

    @JvmStatic
    fun writeReopenFlag(context: Context) {
        try {
            File(dpeDir(context), REOPEN_FILE).writeText("1")
        } catch (_: Exception) { /* best-effort */ }
    }

    @JvmStatic
    fun consumeReopenFlag(context: Context): Boolean {
        return try {
            val f = File(dpeDir(context), REOPEN_FILE)
            if (!f.exists()) return false
            f.delete()
            true
        } catch (_: Exception) { false }
    }
}
