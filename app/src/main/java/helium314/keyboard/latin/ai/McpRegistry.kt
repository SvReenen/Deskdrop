// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Registry of user-configured MCP (Model Context Protocol) servers.
 *
 * Servers are persisted as a JSON array in SharedPreferences under
 * [PREF_MCP_SERVERS]. Each server has a stable id, display name, URL,
 * optional bearer token, and an enabled flag. Only enabled servers are
 * queried during a chat turn.
 *
 * This registry is intentionally in-process and blocking — the tool loop
 * in [AiServiceSync] already runs on a background thread.
 */
object McpRegistry {

    const val PREF_MCP_SERVERS = "ai_mcp_servers"

    /** Transport: "streamable" (modern POST + JSON/SSE) or "sse" (legacy HTTP+SSE). */
    const val TRANSPORT_STREAMABLE = "streamable"
    const val TRANSPORT_SSE = "sse"

    data class McpServer(
        val id: String,
        val name: String,
        val url: String,
        val token: String,
        val enabled: Boolean,
        val transport: String = TRANSPORT_STREAMABLE
    )

    @JvmStatic
    fun listAllServers(prefs: SharedPreferences): List<McpServer> {
        val raw = prefs.getString(PREF_MCP_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<McpServer>()
            var migrated = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { continue }
                val name = o.optString("name").ifBlank { continue }
                val url = o.optString("url").ifBlank { continue }
                // Tokens live in EncryptedSharedPreferences (SecureApiKeys), not in
                // the plain JSON. On first read after upgrade, migrate any plaintext
                // token that's still sitting in the JSON blob into encrypted storage.
                val encryptedToken = SecureApiKeys.getMcpToken(id)
                val legacyToken = o.optString("token", "")
                val token: String = when {
                    encryptedToken.isNotBlank() -> encryptedToken
                    legacyToken.isNotBlank() -> {
                        SecureApiKeys.setMcpToken(id, legacyToken)
                        migrated = true
                        legacyToken
                    }
                    else -> ""
                }
                out.add(
                    McpServer(
                        id = id,
                        name = name,
                        url = url,
                        token = token,
                        enabled = o.optBoolean("enabled", true),
                        transport = o.optString("transport", TRANSPORT_STREAMABLE).ifBlank { TRANSPORT_STREAMABLE }
                    )
                )
            }
            if (migrated) {
                // Rewrite the JSON without plaintext tokens so legacy values no
                // longer sit on disk once encrypted storage has them.
                saveAll(prefs, out)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    @JvmStatic
    fun listEnabledServers(prefs: SharedPreferences): List<McpServer> =
        listAllServers(prefs).filter { it.enabled }

    private fun saveAll(prefs: SharedPreferences, servers: List<McpServer>) {
        val arr = JSONArray()
        for (s in servers) {
            // Persist the token to encrypted storage; the plain JSON blob only
            // holds non-sensitive metadata (id, name, url, enabled, transport).
            SecureApiKeys.setMcpToken(s.id, s.token)
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("url", s.url)
                put("enabled", s.enabled)
                put("transport", s.transport)
            })
        }
        prefs.edit().putString(PREF_MCP_SERVERS, arr.toString()).apply()
    }

    @JvmStatic
    fun addServer(
        prefs: SharedPreferences,
        name: String,
        url: String,
        token: String,
        enabled: Boolean = true,
        transport: String = TRANSPORT_STREAMABLE
    ): McpServer {
        val server = McpServer(
            id = "mcp_${UUID.randomUUID().toString().take(8)}",
            name = name,
            url = url,
            token = token,
            enabled = enabled,
            transport = transport
        )
        saveAll(prefs, listAllServers(prefs) + server)
        invalidateCache()
        return server
    }

    @JvmStatic
    fun updateServer(prefs: SharedPreferences, updated: McpServer) {
        val list = listAllServers(prefs).map { if (it.id == updated.id) updated else it }
        saveAll(prefs, list)
        invalidateCache()
    }

    @JvmStatic
    fun removeServer(prefs: SharedPreferences, id: String) {
        saveAll(prefs, listAllServers(prefs).filter { it.id != id })
        // Wipe the encrypted token too so a deleted server doesn't leave a
        // dangling bearer behind.
        SecureApiKeys.removeMcpToken(id)
        invalidateCache()
    }

    @JvmStatic
    fun setEnabled(prefs: SharedPreferences, id: String, enabled: Boolean) {
        val list = listAllServers(prefs).map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        saveAll(prefs, list)
        invalidateCache()
    }

    // ────────────────────────────────────────────────────────────────────
    // Tool cache: avoid re-listing tools on every chat turn
    // ────────────────────────────────────────────────────────────────────

    private data class CachedTools(
        val tools: List<McpClient.McpToolInfo>,
        val fetchedAt: Long
    )

    private val toolCache = java.util.concurrent.ConcurrentHashMap<String, CachedTools>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Get the list of tools for [server], using a short in-memory cache.
     * On cache miss this performs initialize + tools/list synchronously.
     */
    @JvmStatic
    fun getToolsForServer(server: McpServer): List<McpClient.McpToolInfo> {
        val key = "${server.id}:${server.url}"
        val cached = toolCache[key]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
            return cached.tools
        }
        // cold fetch
        if (!McpClient.initialize(server)) {
            return emptyList()
        }
        val tools = McpClient.listTools(server)
        toolCache[key] = CachedTools(tools, now)
        return tools
    }

    @JvmStatic
    fun invalidateCache() {
        toolCache.clear()
        try { McpClient.closeAllSessions() } catch (_: Throwable) {}
    }
}
