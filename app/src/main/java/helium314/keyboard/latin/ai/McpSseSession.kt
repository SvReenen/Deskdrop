// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Legacy MCP HTTP+SSE transport (pre-2025-03-26 spec).
 *
 * Flow:
 *  1. Client GETs the SSE URL. Server keeps the connection open as a
 *     `text/event-stream`.
 *  2. First event is `event: endpoint` with `data: <post URL>` — this is
 *     the endpoint the client must POST JSON-RPC requests to (typically
 *     path-only with a session token baked in).
 *  3. Client POSTs JSON-RPC requests to that endpoint. The server
 *     acknowledges with 202 Accepted and the actual JSON-RPC *response*
 *     arrives asynchronously as an `event: message` on the SSE stream.
 *  4. Responses are correlated to requests by the JSON-RPC `id`.
 *
 * Concurrency: a single reader thread owns the SSE input stream and
 * delivers matching messages to a per-id [LinkedBlockingQueue]. Callers
 * block on [rpc] until their id's response arrives or the timeout expires.
 *
 * This class is intended to be cached one-per-server in [McpClient] so
 * the SSE connection stays open across multiple tool calls in the same
 * chat session. Call [close] on teardown.
 */
class McpSseSession(
    private val server: McpRegistry.McpServer
) {
    @Volatile private var sseConn: HttpURLConnection? = null
    private val stopped = AtomicBoolean(false)
    private val endpointLatch = CountDownLatch(1)
    @Volatile private var postEndpoint: String? = null
    private val pending = ConcurrentHashMap<Long, LinkedBlockingQueue<JSONObject>>()
    private val idCounter = AtomicLong(1)
    private var readerThread: Thread? = null
    @Volatile private var ready = false

    fun isAlive(): Boolean = ready && !stopped.get()

    /**
     * Open the SSE connection and wait (up to [timeoutMs]) for the first
     * `endpoint` event. Returns false on any failure; the session is
     * left closed and can be retried by creating a new instance.
     */
    fun open(timeoutMs: Long = 15_000): Boolean {
        if (isAlive()) return true
        if (stopped.get()) return false
        return try {
            val url = URL(server.url)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept-Encoding", "identity") // no gzip — must stream
                if (server.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${server.token}")
                }
                connectTimeout = 10_000
                readTimeout = 0 // 0 = infinite, SSE streams stay open
                doInput = true
            }
            sseConn = conn
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                Log.w("McpSse", "SSE GET failed: HTTP $code $err")
                close()
                return false
            }
            val ct = (conn.contentType ?: "").lowercase()
            if (!ct.contains("text/event-stream")) {
                Log.w("McpSse", "Expected text/event-stream, got: $ct")
                close()
                return false
            }
            readerThread = Thread({ readerLoop() }, "mcp-sse-${server.id}").apply {
                isDaemon = true
                start()
            }
            // Wait for `endpoint` event (usually instant)
            if (!endpointLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w("McpSse", "timed out waiting for endpoint event from ${server.name}")
                close()
                return false
            }
            if (postEndpoint == null) {
                Log.w("McpSse", "no postEndpoint after latch release")
                close()
                return false
            }
            ready = true
            Log.d("McpSse", "session ready for ${server.name}, endpoint=$postEndpoint")
            true
        } catch (e: Exception) {
            Log.e("McpSse", "open failed", e)
            close()
            false
        }
    }

    private fun readerLoop() {
        val conn = sseConn ?: return
        val stream = try {
            conn.inputStream
        } catch (e: Exception) {
            Log.w("McpSse", "could not open input stream: ${e.message}")
            // Wake up any waiters
            endpointLatch.countDown()
            return
        }
        val reader = stream.bufferedReader()
        val dataBuf = StringBuilder()
        var eventType = "message"
        try {
            while (!stopped.get()) {
                val line = try {
                    reader.readLine()
                } catch (e: Exception) {
                    if (!stopped.get()) {
                        Log.w("McpSse", "reader error: ${e.message}")
                    }
                    break
                } ?: break
                if (line.isEmpty()) {
                    // end of one SSE event
                    if (dataBuf.isNotEmpty()) {
                        val data = dataBuf.toString()
                        dataBuf.clear()
                        dispatchEvent(eventType, data)
                    }
                    eventType = "message"
                    continue
                }
                if (line.startsWith(":")) continue // comment
                when {
                    line.startsWith("event:") -> eventType = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        val d = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                        if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                        dataBuf.append(d)
                    }
                    // ignore id: and retry:
                }
            }
        } finally {
            Log.d("McpSse", "reader loop exited for ${server.name}")
            // Drain any pending waiters so callers don't hang forever
            pending.values.forEach { q ->
                q.offer(JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", -1)
                    put("error", JSONObject().put("message", "SSE session closed"))
                })
            }
            ready = false
        }
    }

    private fun dispatchEvent(type: String, data: String) {
        when (type) {
            "endpoint" -> {
                val resolved = resolveEndpoint(data.trim())
                postEndpoint = resolved
                endpointLatch.countDown()
                Log.d("McpSse", "endpoint=$resolved")
            }
            "message", "" -> {
                val msg = try { JSONObject(data) } catch (_: Exception) {
                    Log.w("McpSse", "non-JSON SSE data: ${data.take(120)}")
                    null
                } ?: return
                val idVal = msg.opt("id")
                val idLong = when (idVal) {
                    is Number -> idVal.toLong()
                    is String -> idVal.toLongOrNull()
                    else -> null
                }
                if (idLong == null) {
                    // Server-initiated request/notification — ignore in v1
                    return
                }
                val q = pending[idLong]
                if (q != null) {
                    q.offer(msg)
                } else {
                    Log.d("McpSse", "stray response id=$idLong")
                }
            }
            else -> {
                // Unknown event type — log and ignore
                Log.d("McpSse", "unknown event type: $type")
            }
        }
    }

    private fun resolveEndpoint(data: String): String {
        if (data.startsWith("http://") || data.startsWith("https://")) return data
        val base = URL(server.url)
        val port = if (base.port > 0) ":${base.port}" else ""
        val sep = if (data.startsWith("/")) "" else "/"
        return "${base.protocol}://${base.host}$port$sep$data"
    }

    /**
     * Send a JSON-RPC request and block until the response arrives on the
     * SSE stream, up to [timeoutMs]. Returns the unwrapped `result` object
     * (or an empty JSONObject if the server returned no result field), or
     * null on any error.
     */
    fun rpc(method: String, params: JSONObject?, timeoutMs: Long = 30_000): JSONObject? {
        if (!isAlive()) return null
        val endpoint = postEndpoint ?: return null
        val id = idCounter.getAndIncrement()
        val waiter = LinkedBlockingQueue<JSONObject>(1)
        pending[id] = waiter
        try {
            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            }
            if (!postJson(endpoint, req)) return null
            val response = waiter.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (response == null) {
                Log.w("McpSse", "$method timed out waiting for response")
                return null
            }
            val errObj = response.optJSONObject("error")
            if (errObj != null) {
                Log.w("McpSse", "$method RPC error: ${errObj.optString("message")}")
                return null
            }
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    /** Fire a JSON-RPC notification (no id, no response expected). */
    fun notification(method: String, params: JSONObject?) {
        val endpoint = postEndpoint ?: return
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        postJson(endpoint, req)
    }

    private fun postJson(endpoint: String, body: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                if (server.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${server.token}")
                }
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                Log.w("McpSse", "POST got HTTP $code $err")
                false
            } else {
                // Drain (small) response body so the connection can be reused
                try { conn.inputStream.use { it.readBytes() } } catch (_: Exception) {}
                true
            }
        } catch (e: Exception) {
            Log.e("McpSse", "POST failed", e)
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun close() {
        if (stopped.getAndSet(true)) return
        ready = false
        try { sseConn?.disconnect() } catch (_: Exception) {}
        sseConn = null
        // Wake up pending waiters
        pending.values.forEach { q ->
            q.offer(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", -1)
                put("error", JSONObject().put("message", "SSE session closed"))
            })
        }
        pending.clear()
    }
}
