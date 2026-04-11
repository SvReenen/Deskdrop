// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

/**
 * Per-call chat context propagated from [ConversationActivity] down to tools
 * executed inside [AiServiceSync.chatCompletionWithTools]. Modelled after
 * [AiCancelRegistry]: a single volatile slot, set by the caller before the
 * LLM round and cleared in a finally block.
 *
 * Needed because tool implementations (e.g. `set_reminder`) need to know
 * which chat they were invoked from, but passing that through the public
 * chatCompletionWithTools signature would break MCP compatibility.
 */
object AiChatContextRegistry {

    @Volatile private var chatId: String? = null

    @JvmStatic
    fun set(id: String?) {
        chatId = id
    }

    @JvmStatic
    fun clear() {
        chatId = null
    }

    @JvmStatic
    fun currentChatId(): String? = chatId
}
