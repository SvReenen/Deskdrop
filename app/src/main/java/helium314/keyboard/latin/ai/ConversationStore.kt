// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * On-disk persistence for conversation chats.
 *
 * Layout under [Context.getNoBackupFilesDir]:
 * ```
 * conversations/
 *   index.json                       // [{id, title, updatedAt}, …]
 *   <uuid>.json                      // full chat
 * ```
 *
 * All callers should wrap calls in [kotlinx.coroutines.Dispatchers.IO]; this
 * object does not switch threads itself.
 */
object ConversationStore {

    data class ChatMeta(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val pinned: Boolean = false
    )

    data class StoredAttachment(
        val path: String,
        val mimeType: String
    )

    data class StoredMessage(
        val id: String = UUID.randomUUID().toString(),
        val role: String,
        val content: String,
        val modelLabel: String,
        val isError: Boolean,
        val attachments: List<StoredAttachment> = emptyList(),
        val tokenCount: Int? = null,
        val sortOrder: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class StoredChat(
        val id: String,
        val title: String,
        val model: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<StoredMessage>,
        val pinned: Boolean = false,
        val systemPrompt: String = "",
        val temperature: Float? = null
    )

    private const val DIR_NAME = "conversations"
    private const val INDEX_FILE = "index.json"
    private const val SCHEMA_VERSION = 1

    fun newId(): String = UUID.randomUUID().toString()

    fun deriveTitle(firstUserMessage: String): String {
        val trimmed = firstUserMessage.trim().replace(Regex("\\s+"), " ")
        return if (trimmed.length <= 40) trimmed else trimmed.take(40).trimEnd() + "…"
    }

    private fun dir(context: Context): File {
        val d = File(context.noBackupFilesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(context: Context): File = File(dir(context), INDEX_FILE)

    private fun chatFile(context: Context, id: String): File = File(dir(context), "$id.json")

    /**
     * Per-chat folder for attachment files (images, etc). Lives next to the
     * chat JSON so a [delete] of the chat removes attachments in lock-step.
     * Callers must mkdirs() before writing.
     */
    fun chatAttachmentsDir(context: Context, id: String): File = File(dir(context), id)

    private fun readIndex(context: Context): MutableList<ChatMeta> {
        val f = indexFile(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<ChatMeta>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ChatMeta(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        updatedAt = o.optLong("updatedAt", 0L),
                        pinned = o.optBoolean("pinned", false)
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeIndex(context: Context, list: List<ChatMeta>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("title", m.title)
                put("updatedAt", m.updatedAt)
                put("pinned", m.pinned)
            })
        }
        val tmp = File(dir(context), "$INDEX_FILE.tmp")
        tmp.writeText(arr.toString())
        val target = indexFile(context)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    @Synchronized
    fun listAll(context: Context): List<ChatMeta> {
        return readIndex(context).sortedWith(
            compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updatedAt }
        )
    }

    @Synchronized
    fun load(context: Context, id: String): StoredChat? {
        val f = chatFile(context, id)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val version = o.optInt("schemaVersion", 0)
            // Future migrations go here:
            // if (version < 2) migrateV1toV2(o)
            // For now v0→v1 is a no-op (just adds the field on next save).
            val msgsArr = o.optJSONArray("messages") ?: JSONArray()
            val msgs = mutableListOf<StoredMessage>()
            for (i in 0 until msgsArr.length()) {
                val m = msgsArr.optJSONObject(i) ?: continue
                val attsArr = m.optJSONArray("attachments")
                val atts = if (attsArr != null) {
                    val list = mutableListOf<StoredAttachment>()
                    for (j in 0 until attsArr.length()) {
                        val a = attsArr.optJSONObject(j) ?: continue
                        val path = a.optString("path")
                        if (path.isBlank()) continue
                        list.add(StoredAttachment(path, a.optString("mimeType", "image/jpeg")))
                    }
                    list
                } else emptyList()
                msgs.add(
                    StoredMessage(
                        id = m.optString("id", UUID.randomUUID().toString()),
                        role = m.optString("role"),
                        content = m.optString("content"),
                        modelLabel = m.optString("modelLabel"),
                        isError = m.optBoolean("isError", false),
                        attachments = atts,
                        tokenCount = if (m.has("tokenCount")) m.optInt("tokenCount") else null,
                        sortOrder = m.optInt("sortOrder", i),
                        createdAt = m.optLong("createdAt", 0L)
                    )
                )
            }
            StoredChat(
                id = o.optString("id"),
                title = o.optString("title"),
                model = o.optString("model"),
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L),
                messages = msgs,
                pinned = o.optBoolean("pinned", false),
                systemPrompt = o.optString("systemPrompt", ""),
                temperature = if (o.has("temperature")) o.optDouble("temperature").toFloat() else null
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun save(context: Context, chat: StoredChat) {
        val msgsArr = JSONArray()
        for ((i, m) in chat.messages.withIndex()) {
            msgsArr.put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role)
                put("content", m.content)
                put("modelLabel", m.modelLabel)
                put("isError", m.isError)
                put("sortOrder", if (m.sortOrder > 0) m.sortOrder else i)
                put("createdAt", if (m.createdAt > 0) m.createdAt else chat.createdAt)
                if (m.attachments.isNotEmpty()) {
                    val arr = JSONArray()
                    for (a in m.attachments) {
                        arr.put(JSONObject().apply {
                            put("path", a.path)
                            put("mimeType", a.mimeType)
                        })
                    }
                    put("attachments", arr)
                }
                if (m.tokenCount != null) put("tokenCount", m.tokenCount)
            })
        }
        val o = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("id", chat.id)
            put("title", chat.title)
            put("model", chat.model)
            put("createdAt", chat.createdAt)
            put("updatedAt", chat.updatedAt)
            put("messages", msgsArr)
            put("pinned", chat.pinned)
            put("systemPrompt", chat.systemPrompt)
            if (chat.temperature != null) put("temperature", chat.temperature.toDouble())
        }
        val target = chatFile(context, chat.id)
        val tmp = File(dir(context), "${chat.id}.json.tmp")
        tmp.writeText(o.toString())
        if (target.exists()) target.delete()
        tmp.renameTo(target)

        // Update index
        val index = readIndex(context)
        val existing = index.indexOfFirst { it.id == chat.id }
        val meta = ChatMeta(chat.id, chat.title, chat.updatedAt, chat.pinned)
        if (existing >= 0) index[existing] = meta else index.add(meta)
        writeIndex(context, index)
    }

    @Synchronized
    fun setPinned(context: Context, id: String, pinned: Boolean) {
        val chat = load(context, id) ?: return
        save(context, chat.copy(pinned = pinned))
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val f = chatFile(context, id)
        if (f.exists()) f.delete()
        val attDir = chatAttachmentsDir(context, id)
        if (attDir.exists()) attDir.deleteRecursively()
        val index = readIndex(context)
        index.removeAll { it.id == id }
        writeIndex(context, index)
        // Cascade: cancel + remove every reminder bound to this chat,
        // otherwise they linger in ReminderStore as orphans and keep
        // inflating the toolbar unread badge.
        try {
            for (r in ReminderStore.all(context).filter { it.chatId == id }) {
                ReminderScheduler.cancel(context, r.id)
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun rename(context: Context, id: String, newTitle: String) {
        val chat = load(context, id) ?: return
        val updated = chat.copy(title = newTitle, updatedAt = System.currentTimeMillis())
        save(context, updated)
    }
}
