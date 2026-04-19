// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for syncing conversations with the Deskdrop desktop app.
 * All methods are blocking and should be called from a background thread.
 */
object SyncClient {

    private const val TAG = "SyncClient"

    data class SyncResult(
        val conversations: List<ConversationStore.StoredChat>,
        val timestamp: Long
    )

    /** Pull all changes since [sinceMs] from the desktop. */
    fun pull(serverUrl: String, token: String, sinceMs: Long = 0): SyncResult? {
        return try {
            val url = "$serverUrl/api/sync/pull?since=$sinceMs"
            val json = httpGet(url, token) ?: return null
            val obj = JSONObject(json)
            val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

            val convs = mutableListOf<ConversationStore.StoredChat>()
            val convsArr = obj.optJSONArray("conversations") ?: JSONArray()
            val msgsArr = obj.optJSONArray("messages") ?: JSONArray()

            // Group messages by conversationId
            val msgsByConv = mutableMapOf<String, MutableList<JSONObject>>()
            for (i in 0 until msgsArr.length()) {
                val m = msgsArr.optJSONObject(i) ?: continue
                val convId = m.optString("conversationId", m.optString("conversation_id"))
                msgsByConv.getOrPut(convId) { mutableListOf() }.add(m)
            }

            for (i in 0 until convsArr.length()) {
                val c = convsArr.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val msgs = msgsByConv[id]?.sortedBy { it.optInt("sortOrder", 0) }
                    ?.map { parseMessage(it) } ?: emptyList()

                convs.add(ConversationStore.StoredChat(
                    id = id,
                    title = c.optString("title"),
                    model = c.optString("model"),
                    createdAt = c.optLong("createdAt", c.optLong("created_at", 0L)),
                    updatedAt = c.optLong("updatedAt", c.optLong("updated_at", 0L)),
                    messages = msgs,
                    pinned = c.optBoolean("pinned", false),
                    systemPrompt = c.optString("systemPrompt", c.optString("system_prompt", "")),
                    temperature = if (c.has("temperature") && !c.isNull("temperature"))
                        c.optDouble("temperature").toFloat() else null
                ))
            }

            SyncResult(convs, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Pull failed", e)
            null
        }
    }

    /** Push local conversations and messages to the desktop. */
    fun push(serverUrl: String, token: String, chats: List<ConversationStore.StoredChat>): Boolean {
        return try {
            val convsArr = JSONArray()
            val msgsArr = JSONArray()

            for (chat in chats) {
                convsArr.put(JSONObject().apply {
                    put("id", chat.id)
                    put("title", chat.title)
                    put("model", chat.model)
                    put("systemPrompt", chat.systemPrompt)
                    if (chat.temperature != null) put("temperature", chat.temperature.toDouble())
                    put("pinned", chat.pinned)
                    put("createdAt", chat.createdAt)
                    put("updatedAt", chat.updatedAt)
                })

                for ((i, msg) in chat.messages.withIndex()) {
                    msgsArr.put(JSONObject().apply {
                        put("id", msg.id)
                        put("conversationId", chat.id)
                        put("role", msg.role)
                        put("content", msg.content)
                        put("modelLabel", msg.modelLabel)
                        put("isError", msg.isError)
                        if (msg.tokenCount != null) put("tokenCount", msg.tokenCount)
                        put("sortOrder", if (msg.sortOrder > 0) msg.sortOrder else i)
                        put("createdAt", if (msg.createdAt > 0) msg.createdAt else chat.createdAt)
                    })
                }
            }

            val body = JSONObject().apply {
                put("conversations", convsArr)
                put("messages", msgsArr)
            }

            val url = "$serverUrl/api/sync/push"
            val response = httpPost(url, token, body.toString())
            response != null
        } catch (e: Exception) {
            Log.e(TAG, "Push failed", e)
            false
        }
    }

    /** Test connection to the desktop server. */
    fun testConnection(serverUrl: String, token: String): Boolean {
        return try {
            val response = httpGet("$serverUrl/api/conversations", token)
            response != null
        } catch (_: Exception) {
            false
        }
    }

    private fun parseMessage(m: JSONObject): ConversationStore.StoredMessage {
        return ConversationStore.StoredMessage(
            id = m.optString("id"),
            role = m.optString("role"),
            content = m.optString("content"),
            modelLabel = m.optString("modelLabel", m.optString("model_label", "")),
            isError = m.optBoolean("isError", m.optBoolean("is_error", false)),
            tokenCount = if (m.has("tokenCount")) m.optInt("tokenCount")
                else if (m.has("token_count") && !m.isNull("token_count")) m.optInt("token_count")
                else null,
            sortOrder = m.optInt("sortOrder", m.optInt("sort_order", 0)),
            createdAt = m.optLong("createdAt", m.optLong("created_at", 0L))
        )
    }

    private var encKey: ByteArray? = null

    private fun getEncKey(token: String): ByteArray {
        if (encKey == null) encKey = PayloadEncryption.deriveKey(token)
        return encKey!!
    }

    private fun httpGet(url: String, token: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-Deskdrop-Encrypted", "1")
        return try {
            if (conn.responseCode == 200) {
                val responseBody = conn.inputStream.bufferedReader().readText()
                val isEncrypted = conn.getHeaderField("X-Deskdrop-Encrypted") != null
                if (isEncrypted) {
                    PayloadEncryption.decrypt(responseBody, getEncKey(token))
                } else {
                    responseBody
                }
            } else {
                Log.w(TAG, "GET $url returned ${conn.responseCode}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, token: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-Deskdrop-Encrypted", "1")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        return try {
            val encrypted = PayloadEncryption.encrypt(body, getEncKey(token))
            conn.outputStream.use { it.write(encrypted.toByteArray()) }
            if (conn.responseCode == 200) {
                val responseBody = conn.inputStream.bufferedReader().readText()
                val isEncrypted = conn.getHeaderField("X-Deskdrop-Encrypted") != null
                if (isEncrypted) {
                    val decrypted = PayloadEncryption.decrypt(responseBody, getEncKey(token))
                    if (decrypted == null) Log.w(TAG, "POST response decryption failed, body length=${responseBody.length}")
                    decrypted
                } else {
                    responseBody
                }
            } else {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                Log.w(TAG, "POST $url returned ${conn.responseCode}: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
