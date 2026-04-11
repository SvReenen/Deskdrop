// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP (Model Context Protocol) client — Streamable HTTP transport.
 *
 * Implements the JSON-RPC 2.0 wire protocol per the 2025-03-26 MCP spec
 * (“Streamable HTTP”). A single HTTP POST endpoint per server is used for
 * every request; the server replies with either:
 *
 *  - `Content-Type: application/json` — a single JSON-RPC response object
 *  - `Content-Type: text/event-stream` — an SSE stream containing one or
 *    more JSON-RPC messages; we read until we find the one matching our
 *    request id and then close the connection.
 *
 * Session handling follows the spec:
 *  - If the server returns a `Mcp-Session-Id` header on `initialize`, we
 *    persist it per-server and include it on every subsequent request.
 *  - After `initialize` we send the required `notifications/initialized`
 *    notification so servers that gate further calls on this signal work.
 *
 * This is still synchronous and blocks — the tool-calling loop in
 * [AiServiceSync] runs on a background thread so blocking here is fine.
 * Server-initiated requests (sampling, roots/list) are not supported in
 * this client: we just drop any messages on the stream that don’t match
 * our own request id.
 */
object McpClient {

    private val idCounter = AtomicLong(1)
    private const val PROTOCOL_VERSION = "2025-03-26"
    private const val TIMEOUT_MS = 30_000

    /** Per-server session ids, captured from the `Mcp-Session-Id` response header. */
    private val sessions = ConcurrentHashMap<String, String>()

    /** Per-server legacy HTTP+SSE sessions (kept alive across calls). */
    private val sseSessions = ConcurrentHashMap<String, McpSseSession>()

    private fun getOrOpenSseSession(server: McpRegistry.McpServer): McpSseSession? {
        val existing = sseSessions[server.id]
        if (existing != null && existing.isAlive()) return existing
        existing?.close()
        sseSessions.remove(server.id)
        val session = McpSseSession(server)
        if (!session.open()) {
            return null
        }
        sseSessions[server.id] = session
        return session
    }

    data class McpToolInfo(
        val name: String,
        val description: String,
        val inputSchema: JSONObject
    )

    private fun nextId(): Long = idCounter.getAndIncrement()

    /**
     * Send a single JSON-RPC request and return the parsed "result" object.
     * Returns null on any transport or protocol error (logged).
     */
    private fun rpc(server: McpRegistry.McpServer, method: String, params: JSONObject?): JSONObject? {
        val id = nextId()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(server.url)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                // Required per spec: advertise both JSON and SSE so server can pick
                setRequestProperty("Accept", "application/json, text/event-stream")
                if (server.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${server.token}")
                }
                sessions[server.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            }
            conn.outputStream.use { it.write(req.toString().toByteArray()) }

            // Capture session id set by server on initialize (or any other response)
            conn.getHeaderField("Mcp-Session-Id")?.let { sid ->
                if (sid.isNotBlank()) sessions[server.id] = sid
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                Log.w("McpClient", "$method failed: HTTP $code $err")
                // If session expired (404), clear and let caller retry from initialize
                if (code == 404) sessions.remove(server.id)
                return null
            }

            val contentType = (conn.contentType ?: "").lowercase()
            val body: JSONObject? = if (contentType.contains("text/event-stream")) {
                findResponseInSse(conn.inputStream, id)
            } else {
                try {
                    JSONObject(conn.inputStream.bufferedReader().readText())
                } catch (e: Exception) {
                    Log.w("McpClient", "$method: invalid JSON response: ${e.message}")
                    null
                }
            }

            if (body == null) {
                Log.w("McpClient", "$method: no matching response body")
                return null
            }
            val errObj = body.optJSONObject("error")
            if (errObj != null) {
                Log.w("McpClient", "$method RPC error: ${errObj.optString("message")}")
                return null
            }
            body.optJSONObject("result") ?: JSONObject()
        } catch (e: Exception) {
            Log.e("McpClient", "$method exception", e)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Send a JSON-RPC notification (no id, no response expected). The
     * server is expected to return 202 Accepted. We don’t parse the body.
     */
    private fun sendNotification(server: McpRegistry.McpServer, method: String, params: JSONObject?) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(server.url)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                if (server.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${server.token}")
                }
                sessions[server.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            }
            conn.outputStream.use { it.write(req.toString().toByteArray()) }
            // Force the request to flush by reading the status
            val code = conn.responseCode
            if (code !in 200..299 && code != 202) {
                Log.w("McpClient", "notification $method got HTTP $code")
            }
        } catch (e: Exception) {
            Log.w("McpClient", "notification $method exception: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Read an SSE stream line-by-line until we find a JSON-RPC message
     * whose `id` matches [targetId]. Messages with different ids (or
     * server-initiated notifications/requests) are logged and skipped.
     *
     * SSE framing: each event is a block of lines ending in a blank line.
     * Lines starting with "data:" contain payload; multiple data lines in
     * one event are concatenated with newlines.
     */
    private fun findResponseInSse(stream: InputStream, targetId: Long): JSONObject? {
        val reader = stream.bufferedReader()
        val dataBuf = StringBuilder()
        // Safety cap: avoid reading forever if the server misbehaves
        var linesRead = 0
        val maxLines = 20_000
        while (linesRead < maxLines) {
            val line = reader.readLine() ?: break
            linesRead++
            if (line.isEmpty()) {
                // end of one SSE event — try parsing buffered data as JSON-RPC
                if (dataBuf.isNotEmpty()) {
                    val payload = dataBuf.toString()
                    dataBuf.clear()
                    val msg = try { JSONObject(payload) } catch (_: Exception) { null }
                    if (msg != null) {
                        val idVal = msg.opt("id")
                        val matches = when (idVal) {
                            is Number -> idVal.toLong() == targetId
                            is String -> idVal == targetId.toString()
                            else -> false // notification / different shape → ignore
                        }
                        if (matches) return msg
                        // Non-matching id = either a server-initiated request or
                        // a stray message. We don’t handle those in v1.
                    }
                }
                continue
            }
            if (line.startsWith(":")) continue // SSE comment
            if (line.startsWith("data:")) {
                val data = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                dataBuf.append(data)
            }
            // ignore other SSE fields (event:, id:, retry:)
        }
        return null
    }

    /**
     * Perform the MCP initialize handshake plus the required
     * `notifications/initialized` follow-up. Returns true if the handshake
     * succeeded. Clears any stale session id for this server first so a
     * reconnect after session expiry works cleanly.
     */
    fun initialize(server: McpRegistry.McpServer): Boolean {
        val initParams = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject().put("tools", JSONObject()))
            put("clientInfo", JSONObject().apply {
                put("name", "Deskdrop")
                put("version", "1.0")
            })
        }
        return if (server.transport == McpRegistry.TRANSPORT_SSE) {
            // Drop any stale session and open a fresh SSE connection
            sseSessions[server.id]?.close()
            sseSessions.remove(server.id)
            val session = getOrOpenSseSession(server) ?: return false
            val result = session.rpc("initialize", initParams) ?: return false
            session.notification("notifications/initialized", null)
            Log.d("McpClient", "[sse] initialized ${server.name}: protocol=${result.optString("protocolVersion", "?")}")
            true
        } else {
            sessions.remove(server.id)
            val result = rpc(server, "initialize", initParams) ?: return false
            sendNotification(server, "notifications/initialized", null)
            Log.d("McpClient", "[streamable] initialized ${server.name}: protocol=${result.optString("protocolVersion", "?")}")
            true
        }
    }

    private fun doRpc(server: McpRegistry.McpServer, method: String, params: JSONObject?): JSONObject? {
        return if (server.transport == McpRegistry.TRANSPORT_SSE) {
            val session = getOrOpenSseSession(server) ?: return null
            session.rpc(method, params)
        } else {
            rpc(server, method, params)
        }
    }

    /** Discover tools exposed by [server]. Returns empty list on failure. */
    fun listTools(server: McpRegistry.McpServer): List<McpToolInfo> {
        val result = doRpc(server, "tools/list", null) ?: return emptyList()
        val toolsArr = result.optJSONArray("tools") ?: return emptyList()
        val out = mutableListOf<McpToolInfo>()
        for (i in 0 until toolsArr.length()) {
            val t = toolsArr.optJSONObject(i) ?: continue
            val name = t.optString("name")
            if (name.isBlank()) continue
            out.add(
                McpToolInfo(
                    name = name,
                    description = t.optString("description", ""),
                    inputSchema = t.optJSONObject("inputSchema") ?: JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    }
                )
            )
        }
        return out
    }

    /**
     * Call [toolName] on [server] with [args]. Returns a tool result string
     * suitable for feeding back to the LLM, plus an error flag.
     */
    fun callTool(
        server: McpRegistry.McpServer,
        toolName: String,
        args: JSONObject
    ): AiToolRegistry.ToolResult {
        val params = JSONObject().apply {
            put("name", toolName)
            put("arguments", args)
        }
        val result = doRpc(server, "tools/call", params)
            ?: return AiToolRegistry.ToolResult("MCP call failed", isError = true)
        // Result shape: { content: [{type:"text", text:"..."}, ...], isError?: bool }
        val isError = result.optBoolean("isError", false)
        val contentArr = result.optJSONArray("content")
        if (contentArr == null || contentArr.length() == 0) {
            return AiToolRegistry.ToolResult(
                if (isError) "Tool reported error" else "(empty result)",
                isError = isError
            )
        }
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val c = contentArr.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "text" -> {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(c.optString("text", ""))
                }
                "resource" -> {
                    // Inline resource contents if text
                    val res = c.optJSONObject("resource")
                    val text = res?.optString("text", "")
                    if (!text.isNullOrEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(text)
                    }
                }
                // image / audio types are ignored — the LLM can't act on them
                // without vision plumbing; not worth it in v1.
            }
        }
        return AiToolRegistry.ToolResult(
            sb.toString().ifEmpty { "(non-text content)" },
            isError = isError
        )
    }

    /**
     * Explicit session teardown. Called when a server is removed or edited
     * so that the next use starts a fresh handshake. For legacy SSE this
     * also closes the persistent stream connection. For Streamable HTTP
     * we only drop our copy of the session id (the spec says clients MAY
     * send an HTTP DELETE, but it's optional).
     */
    @Suppress("unused")
    fun forgetSession(serverId: String) {
        sessions.remove(serverId)
        sseSessions.remove(serverId)?.close()
    }

    /** Close every active SSE session and clear all session ids. */
    @JvmStatic
    fun closeAllSessions() {
        sessions.clear()
        sseSessions.values.toList().forEach { try { it.close() } catch (_: Throwable) {} }
        sseSessions.clear()
    }
}
