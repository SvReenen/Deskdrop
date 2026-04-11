// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.utils.ToolbarKey
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized registry for cancelling in-flight AI calls.
 *
 * Flow:
 *  1. Caller starts an AI operation and receives a [CancelHandle]
 *  2. HTTP helpers register their [HttpURLConnection] on the handle
 *  3. A second tap on the same toolbar button calls [cancel] which
 *     disconnects the live connection from another thread
 *  4. The worker thread checks [CancelHandle.cancelled] after the IOException
 *     and aborts the commit on the UI thread.
 *
 * Only one AI call is tracked at a time; existing code already prevents
 * concurrent AI calls via mAiProcessing.
 */
object AiCancelRegistry {

    data class TokenUsage(val promptTokens: Int, val completionTokens: Int) {
        val total get() = promptTokens + completionTokens
    }

    class CancelHandle(val key: ToolbarKey) {
        @Volatile var connection: HttpURLConnection? = null
        val cancelled = AtomicBoolean(false)
        @Volatile var tokenUsage: TokenUsage? = null
    }

    @Volatile private var current: CancelHandle? = null

    @Synchronized
    fun start(key: ToolbarKey): CancelHandle {
        val h = CancelHandle(key)
        current = h
        return h
    }

    @Synchronized
    fun clear(handle: CancelHandle) {
        if (current === handle) {
            current = null
        }
    }

    @JvmStatic
    fun getActiveKey(): ToolbarKey? = current?.key

    /**
     * Cancels the currently active AI call, if any.
     * Returns true if a call was cancelled.
     */
    @JvmStatic
    @Synchronized
    fun cancel(): Boolean {
        val h = current ?: return false
        h.cancelled.set(true)
        val conn = h.connection
        if (conn != null) {
            // disconnect() from another thread forces the blocking read to throw IOException.
            // Run on a background thread because disconnect() can block briefly.
            Thread { try { conn.disconnect() } catch (_: Exception) {} }.start()
        }
        current = null
        return true
    }

    @JvmStatic
    fun isCancelled(handle: CancelHandle?): Boolean = handle?.cancelled?.get() == true
}
