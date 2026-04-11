// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.util.Log
import helium314.keyboard.latin.ai.tools.AiToolCatalog
import helium314.keyboard.latin.ai.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Registry of tools the LLM can call. v1 ships three built-in tools:
 *
 * - **calculator** — evaluates an arithmetic expression with a tiny
 *   recursive-descent parser (no eval(), no JS engine, no external deps)
 * - **get_datetime** — returns the current date/time/day-of-week/timezone so
 *   the model stops hallucinating that it is 2023
 * - **fetch_url** — GETs a URL (http/https only), hard-capped at 100 KB, 10 s
 *   timeout, HTML stripped to plain text
 *
 * All three are side-effect-free (read/compute only) so they run automatically
 * without a user-approval step. Network and shell access will be added in a
 * later round with explicit approval UI.
 */
object AiToolRegistry {

    data class ToolDef(
        val name: String,
        val description: String,
        /** JSON Schema object describing parameters. */
        val parametersSchema: JSONObject
    )

    /**
     * Represents a single tool invocation requested by the model. [id] is
     * the provider-supplied call id (Anthropic returns `toolu_…`); for
     * Ollama we generate a local UUID because the Ollama API doesn't track
     * ids itself.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val args: JSONObject
    )

    data class ToolResult(
        val content: String,
        val isError: Boolean = false
    )

    // ────────────────────────────────────────────────────────────────────
    // Tool definitions
    // ────────────────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun availableTools(
        allowNetwork: Boolean = false,
        allowActions: Boolean = false
    ): List<ToolDef> {
        val legacy = allTools().filter { def ->
            when (def.name) {
                "fetch_url", "weather", "web_search" -> allowNetwork
                "set_timer", "open_app", "read_clipboard" -> allowActions
                else -> true
            }
        }
        val catalog = AiToolCatalog.enabled(allowNetwork, allowActions).map { it.toLegacyDef() }
        return legacy + catalog
    }

    private fun allTools(): List<ToolDef> = listOf(
        ToolDef(
            name = "calculator",
            description = "Evaluate an arithmetic expression. Supports + - * / % ^ and parentheses. Use this for any numeric calculation — do NOT guess the result yourself.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("expression", JSONObject().apply {
                        put("type", "string")
                        put("description", "The arithmetic expression to evaluate, e.g. '2843*57' or '(18+24)/6^2'.")
                    })
                })
                put("required", JSONArray().put("expression"))
            }
        ),
        ToolDef(
            name = "get_datetime",
            description = "Return the current date, time, day-of-week and timezone. Use this whenever the user asks about 'today', 'tomorrow', 'now', current year, or anything time-sensitive — your training data is out of date.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        ToolDef(
            name = "fetch_url",
            description = "GET a web page over http/https and return its text content (HTML is stripped to plain text). Max 100 KB, 10 second timeout. Use this when the user asks you to read or summarize a specific URL.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "The full URL to fetch, including http:// or https://.")
                    })
                })
                put("required", JSONArray().put("url"))
            }
        ),
        ToolDef(
            name = "unit_convert",
            description = "Convert a numeric value between units. Supports length (mm cm m km in ft yd mi), mass (mg g kg lb oz), temperature (c f k), volume (ml l gal fl_oz cup), time (s min h day), speed (kmh mph ms knot), data (b kb mb gb tb). Both 'from' and 'to' must be in the same category.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("value", JSONObject().apply {
                        put("type", "number")
                        put("description", "The numeric value to convert.")
                    })
                    put("from", JSONObject().apply {
                        put("type", "string")
                        put("description", "The source unit, e.g. 'in', 'kg', 'c', 'mph'.")
                    })
                    put("to", JSONObject().apply {
                        put("type", "string")
                        put("description", "The target unit, e.g. 'mm', 'lb', 'f', 'kmh'.")
                    })
                })
                put("required", JSONArray().put("value").put("from").put("to"))
            }
        ),
        ToolDef(
            name = "battery_info",
            description = "Return the device's current battery percentage, charging state, and temperature. Use when the user asks about battery.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        ToolDef(
            name = "device_info",
            description = "Return info about the current Android device: manufacturer, model, Android version, locale, timezone, and free storage. Use when the user asks what phone/device they have, their Android version, or free space.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        ToolDef(
            name = "read_clipboard",
            description = "Return the current text contents of the system clipboard. Use when the user asks what is in their clipboard or wants you to act on the most recently copied text.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        ToolDef(
            name = "web_search",
            description = "Search the web via Brave Search and return the top results (title, URL, snippet). Use this when the user asks about recent events, current facts, or anything where you need fresh information. Follow up with fetch_url if you need the full content of a result.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "The search query, e.g. 'latest iOS release notes' or 'weather in Paris'.")
                    })
                    put("max_results", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Number of results to return (1-10, default 5).")
                    })
                })
                put("required", JSONArray().put("query"))
            }
        ),
        ToolDef(
            name = "weather",
            description = "Get current weather for a location (city name or 'city, country'). Returns temperature, conditions, wind and humidity. Uses wttr.in.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("type", "string")
                        put("description", "City name or 'city, country'. Examples: 'Amsterdam', 'Tokyo', 'New York, US'.")
                    })
                })
                put("required", JSONArray().put("location"))
            }
        ),
        ToolDef(
            name = "set_timer",
            description = "Start a countdown timer in the device's Clock app. The user will see the Clock app open with the timer. Max 24 hours.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("seconds", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Timer duration in seconds. Must be between 1 and 86400.")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional label shown on the timer.")
                    })
                })
                put("required", JSONArray().put("seconds"))
            }
        ),
        ToolDef(
            name = "open_app",
            description = "Launch an installed app by package name (e.g. 'com.spotify.music') or natural name (e.g. 'Spotify'). Use when the user asks you to open, launch or start an app.",
            parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("app", JSONObject().apply {
                        put("type", "string")
                        put("description", "Package name or app label.")
                    })
                })
                put("required", JSONArray().put("app"))
            }
        )
    )

    // ────────────────────────────────────────────────────────────────────
    // Dispatch + execution
    // ────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun execute(call: ToolCall): ToolResult {
        return try {
            // Prefer catalog (new AiTool interface) over legacy dispatch.
            val catalogTool = AiToolCatalog.byName(call.name)
            if (catalogTool != null) {
                val ctx = currentToolContext()
                    ?: return ToolResult("App context not initialised", isError = true)
                return catalogTool.execute(call.args, ctx)
            }
            when (call.name) {
                "calculator" -> runCalculator(call.args)
                "get_datetime" -> runDateTime()
                "fetch_url" -> runFetchUrl(call.args)
                "unit_convert" -> runUnitConvert(call.args)
                "battery_info" -> runBatteryInfo()
                "device_info" -> runDeviceInfo()
                "read_clipboard" -> runReadClipboard()
                "weather" -> runWeather(call.args)
                "web_search" -> runWebSearch(call.args)
                "set_timer" -> runSetTimer(call.args)
                "open_app" -> runOpenApp(call.args)
                else -> ToolResult("Unknown tool: ${call.name}", isError = true)
            }
        } catch (e: Exception) {
            Log.e("AiToolRegistry", "Tool ${call.name} failed", e)
            ToolResult("Tool execution failed: ${e.message}", isError = true)
        }
    }

    /**
     * Build a [ToolContext] from the globals the legacy code uses. Returns
     * null when the app context has not been initialised yet (should never
     * happen in practice because the service wires it on first use).
     */
    private fun currentToolContext(): ToolContext? {
        val ctx = AiServiceSync.appContext() ?: return null
        val prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(ctx)
        return ToolContext(
            appContext = ctx,
            chatId = AiChatContextRegistry.currentChatId(),
            prefs = prefs
        )
    }

    /** Package-internal reach to the app context set by [AiServiceSync.setContext]. */
    private fun requireAppContext(): Context =
        AiServiceSync.appContext()
            ?: throw IllegalStateException("App context not initialised")

    /** New random id for tool calls on backends that don't provide one. */
    @JvmStatic
    fun newCallId(): String = "tc_${UUID.randomUUID().toString().take(12)}"

    // ────────────────────────────────────────────────────────────────────
    // Calculator
    // ────────────────────────────────────────────────────────────────────

    private fun runCalculator(args: JSONObject): ToolResult {
        val expr = args.optString("expression").trim()
        if (expr.isBlank()) return ToolResult("Missing 'expression' argument", isError = true)
        return try {
            val parser = ExprParser(expr)
            val result = parser.parse()
            val pretty = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                "%.10g".format(result).trimEnd('0').trimEnd('.')
            }
            ToolResult(pretty)
        } catch (e: Exception) {
            ToolResult("Invalid expression: ${e.message}", isError = true)
        }
    }

    /**
     * Tiny recursive-descent arithmetic parser. Grammar:
     *   expr    = term (('+'|'-') term)*
     *   term    = factor (('*'|'/'|'%') factor)*
     *   factor  = unary ('^' factor)?        // right-associative
     *   unary   = ('+'|'-') unary | primary
     *   primary = '(' expr ')' | NUMBER
     */
    private class ExprParser(val input: String) {
        var pos = 0

        fun parse(): Double {
            val result = parseExpr()
            skipWs()
            if (pos < input.length) {
                throw IllegalArgumentException("Unexpected token at position $pos: '${input.substring(pos)}'")
            }
            return result
        }

        private fun skipWs() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun parseExpr(): Double {
            var left = parseTerm()
            while (true) {
                skipWs()
                if (pos >= input.length) break
                val c = input[pos]
                if (c == '+' || c == '-') {
                    pos++
                    val right = parseTerm()
                    left = if (c == '+') left + right else left - right
                } else break
            }
            return left
        }

        private fun parseTerm(): Double {
            var left = parseFactor()
            while (true) {
                skipWs()
                if (pos >= input.length) break
                val c = input[pos]
                if (c == '*' || c == '/' || c == '%') {
                    pos++
                    val right = parseFactor()
                    left = when (c) {
                        '*' -> left * right
                        '/' -> {
                            if (right == 0.0) throw IllegalArgumentException("Division by zero")
                            left / right
                        }
                        else -> left % right
                    }
                } else break
            }
            return left
        }

        private fun parseFactor(): Double {
            val left = parseUnary()
            skipWs()
            if (pos < input.length && input[pos] == '^') {
                pos++
                val right = parseFactor() // right-associative
                return Math.pow(left, right)
            }
            return left
        }

        private fun parseUnary(): Double {
            skipWs()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of expression")
            val c = input[pos]
            if (c == '-') { pos++; return -parseUnary() }
            if (c == '+') { pos++; return parseUnary() }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of expression")
            val c = input[pos]
            if (c == '(') {
                pos++
                val v = parseExpr()
                skipWs()
                if (pos >= input.length || input[pos] != ')') {
                    throw IllegalArgumentException("Expected ')' at position $pos")
                }
                pos++
                return v
            }
            // Number
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (start == pos) {
                throw IllegalArgumentException("Expected number at position $pos")
            }
            return input.substring(start, pos).toDouble()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Date/time
    // ────────────────────────────────────────────────────────────────────

    private fun runDateTime(): ToolResult {
        val now = java.util.Date()
        val tz = java.util.TimeZone.getDefault()
        val cal = java.util.Calendar.getInstance(tz)
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply { timeZone = tz }
        val dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.ENGLISH).apply { timeZone = tz }
        val fullFmt = java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm zzz", java.util.Locale.ENGLISH).apply { timeZone = tz }

        val todayStr = dateFmt.format(now)
        val todayDay = dayFmt.format(now)

        cal.time = now
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrowStr = dateFmt.format(cal.time)
        val tomorrowDay = dayFmt.format(cal.time)

        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val dayAfterStr = dateFmt.format(cal.time)
        val dayAfterDay = dayFmt.format(cal.time)

        return ToolResult(
            "Now: ${fullFmt.format(now)}\n" +
            "Today: $todayDay $todayStr\n" +
            "Tomorrow: $tomorrowDay $tomorrowStr\n" +
            "Day after tomorrow: $dayAfterDay $dayAfterStr\n" +
            "IMPORTANT: 'tomorrow' = $tomorrowStr, 'day after tomorrow'/'overmorgen' = $dayAfterStr. Do NOT use $todayStr for future dates."
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // URL fetch
    // ────────────────────────────────────────────────────────────────────

    private const val FETCH_MAX_BYTES = 100 * 1024
    private const val FETCH_TIMEOUT_MS = 10_000

    private fun runFetchUrl(args: JSONObject): ToolResult {
        val raw = args.optString("url").trim()
        if (raw.isBlank()) return ToolResult("Missing 'url' argument", isError = true)
        val lower = raw.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return ToolResult("Only http and https URLs are allowed", isError = true)
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(raw)
            // Block private/internal IP ranges to prevent SSRF
            try {
                val addr = java.net.InetAddress.getByName(url.host)
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                    return ToolResult("Cannot fetch private/internal URLs", isError = true)
                }
            } catch (_: java.net.UnknownHostException) {
                return ToolResult("Could not resolve hostname: ${url.host}", isError = true)
            }
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = FETCH_TIMEOUT_MS
            conn.readTimeout = FETCH_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Deskdrop/1.0 (+fetch_url tool)")
            conn.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5")
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                return ToolResult("HTTP $code from $raw", isError = true)
            }
            val stream = conn.inputStream
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            stream.use { s ->
                while (total < FETCH_MAX_BYTES) {
                    val toRead = minOf(buf.size, FETCH_MAX_BYTES - total)
                    val n = s.read(buf, 0, toRead)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            val contentType = conn.contentType ?: "text/html"
            val raw_body = out.toString(Charsets.UTF_8.name())
            val text = if ("html" in contentType.lowercase()) stripHtml(raw_body) else raw_body
            val truncated = if (total >= FETCH_MAX_BYTES) "\n[truncated at 100 KB]" else ""
            ToolResult(text.take(20_000) + truncated)
        } catch (e: Exception) {
            ToolResult("fetch_url failed: ${e.message}", isError = true)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Very rough HTML→plain-text: drop script/style blocks, strip tags,
     * collapse whitespace, decode a handful of named entities. Good enough
     * for feeding page content to a model, not a real parser.
     */
    private fun stripHtml(html: String): String {
        var s = html
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<!--.*?-->").replace(s, " ")
        s = Regex("<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = Regex("[ \\t]+").replace(s, " ")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    // ────────────────────────────────────────────────────────────────────
    // Unit conversion
    // ────────────────────────────────────────────────────────────────────

    /**
     * Linear conversion factors → canonical unit per category.
     * Temperature is special-cased (affine transform), so it's not in this table.
     */
    private val unitCategories: Map<String, Map<String, Double>> = mapOf(
        "length" to mapOf(
            "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344
        ),
        "mass" to mapOf(
            "mg" to 0.000001, "g" to 0.001, "kg" to 1.0,
            "lb" to 0.45359237, "oz" to 0.028349523125
        ),
        "volume" to mapOf(
            "ml" to 0.001, "l" to 1.0,
            "gal" to 3.785411784, "fl_oz" to 0.0295735295625, "cup" to 0.2365882365
        ),
        "time" to mapOf(
            "s" to 1.0, "min" to 60.0, "h" to 3600.0, "day" to 86400.0
        ),
        "speed" to mapOf(
            // canonical: m/s
            "ms" to 1.0, "kmh" to 0.2777777777777778,
            "mph" to 0.44704, "knot" to 0.5144444444444445
        ),
        "data" to mapOf(
            // canonical: bytes (decimal)
            "b" to 1.0, "kb" to 1e3, "mb" to 1e6, "gb" to 1e9, "tb" to 1e12
        )
    )

    private fun findCategory(unit: String): String? {
        val u = unit.lowercase(Locale.ROOT)
        for ((cat, map) in unitCategories) if (u in map) return cat
        if (u == "c" || u == "f" || u == "k") return "temperature"
        return null
    }

    private fun runUnitConvert(args: JSONObject): ToolResult {
        if (!args.has("value")) return ToolResult("Missing 'value'", isError = true)
        val value = args.optDouble("value", Double.NaN)
        if (value.isNaN()) return ToolResult("'value' must be a number", isError = true)
        val from = args.optString("from").trim().lowercase(Locale.ROOT)
        val to = args.optString("to").trim().lowercase(Locale.ROOT)
        if (from.isBlank() || to.isBlank()) return ToolResult("Missing 'from' or 'to'", isError = true)

        val fromCat = findCategory(from) ?: return ToolResult("Unknown unit: $from", isError = true)
        val toCat = findCategory(to) ?: return ToolResult("Unknown unit: $to", isError = true)
        if (fromCat != toCat) {
            return ToolResult("Cannot convert $from ($fromCat) to $to ($toCat)", isError = true)
        }

        val result = if (fromCat == "temperature") {
            convertTemperature(value, from, to)
        } else {
            val map = unitCategories[fromCat]!!
            val canonical = value * map[from]!!
            canonical / map[to]!!
        }
        return ToolResult("${formatNumber(value)} $from = ${formatNumber(result)} $to")
    }

    private fun convertTemperature(value: Double, from: String, to: String): Double {
        // to Kelvin
        val k = when (from) {
            "c" -> value + 273.15
            "f" -> (value - 32.0) * 5.0 / 9.0 + 273.15
            "k" -> value
            else -> throw IllegalArgumentException("bad temperature unit: $from")
        }
        return when (to) {
            "c" -> k - 273.15
            "f" -> (k - 273.15) * 9.0 / 5.0 + 32.0
            "k" -> k
            else -> throw IllegalArgumentException("bad temperature unit: $to")
        }
    }

    private fun formatNumber(d: Double): String {
        if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        return "%.6g".format(Locale.ROOT, d).trimEnd('0').trimEnd('.')
    }

    // ────────────────────────────────────────────────────────────────────
    // Battery / device / clipboard
    // ────────────────────────────────────────────────────────────────────

    private fun runBatteryInfo(): ToolResult {
        val ctx = requireAppContext()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return ToolResult("BatteryManager unavailable", isError = true)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val statusInt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val statusStr = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
        val sticky = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) { null }
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val tempStr = if (tempTenths > 0) ", %.1f°C".format(Locale.ROOT, tempTenths / 10.0) else ""
        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> ", health good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> ", health overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> ", health dead"
            BatteryManager.BATTERY_HEALTH_COLD -> ", health cold"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> ", health over-voltage"
            else -> ""
        }
        return ToolResult("Battery: $pct%, $statusStr$tempStr$healthStr")
    }

    private fun runDeviceInfo(): ToolResult {
        val ctx = requireAppContext()
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "?"
        val model = Build.MODEL ?: "?"
        val androidRelease = Build.VERSION.RELEASE ?: "?"
        val sdk = Build.VERSION.SDK_INT
        val locale = Locale.getDefault().toLanguageTag()
        val tz = TimeZone.getDefault().id
        val freeGb = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes / 1_000_000_000.0
        } catch (_: Exception) { -1.0 }
        val freeStr = if (freeGb >= 0) "%.1f GB free".format(Locale.ROOT, freeGb) else "unknown free space"
        // Keep name the user sees short: "Samsung SM-S911B, Android 14 (API 34), nl-NL, Europe/Amsterdam, 45.2 GB free"
        return ToolResult("$manufacturer $model, Android $androidRelease (API $sdk), $locale, $tz, $freeStr")
    }

    private fun runReadClipboard(): ToolResult {
        val ctx = requireAppContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolResult("ClipboardManager unavailable", isError = true)
        val clip = cm.primaryClip ?: return ToolResult("(clipboard is empty)")
        if (clip.itemCount == 0) return ToolResult("(clipboard is empty)")
        val text = clip.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        if (text.isEmpty()) return ToolResult("(clipboard is empty)")
        val maxLen = 10_000
        return if (text.length > maxLen) ToolResult(text.take(maxLen) + "…[truncated]")
        else ToolResult(text)
    }

    // ────────────────────────────────────────────────────────────────────
    // Weather (wttr.in)
    // ────────────────────────────────────────────────────────────────────

    private fun runWeather(args: JSONObject): ToolResult {
        val location = args.optString("location").trim()
        if (location.isBlank()) return ToolResult("Missing 'location'", isError = true)
        val encoded = URLEncoder.encode(location, "UTF-8")
        val url = "https://wttr.in/$encoded?format=j1"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Deskdrop/1.0 (+weather tool)")
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) return ToolResult("Weather service returned HTTP $code", isError = true)
            val maxBytes = 50 * 1024
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            conn.inputStream.use { s ->
                while (total < maxBytes) {
                    val toRead = minOf(buf.size, maxBytes - total)
                    val n = s.read(buf, 0, toRead)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            val body = out.toString(Charsets.UTF_8.name())
            parseWttrJson(body)
        } catch (e: Exception) {
            ToolResult("weather failed: ${e.message}", isError = true)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parseWttrJson(body: String): ToolResult {
        return try {
            val json = JSONObject(body)
            val curr = json.getJSONArray("current_condition").getJSONObject(0)
            val areaName = try {
                json.getJSONArray("nearest_area").getJSONObject(0)
                    .getJSONArray("areaName").getJSONObject(0).getString("value")
            } catch (_: Exception) { "" }
            val country = try {
                json.getJSONArray("nearest_area").getJSONObject(0)
                    .getJSONArray("country").getJSONObject(0).getString("value")
            } catch (_: Exception) { "" }
            val tempC = curr.optString("temp_C", "?")
            val feelsC = curr.optString("FeelsLikeC", "")
            val desc = try {
                curr.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
            } catch (_: Exception) { "" }
            val wind = curr.optString("windspeedKmph", "?")
            val humidity = curr.optString("humidity", "?")
            val header = listOf(areaName, country).filter { it.isNotBlank() }.joinToString(", ")
            val feelsStr = if (feelsC.isNotBlank() && feelsC != tempC) " (feels like ${feelsC}°C)" else ""
            ToolResult("$header: ${tempC}°C$feelsStr, $desc, wind $wind km/h, humidity $humidity%")
        } catch (e: Exception) {
            ToolResult("Could not parse weather response: ${e.message}", isError = true)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Web search (Tavily → Brave API → Brave HTML)
    //
    // Provider selection order, transparent to the model (tool stays
    // named "web_search"):
    //
    // 1. Tavily (PREF_TAVILY_API_KEY) — LLM-native search. Returns
    //    pre-processed, clean snippets aimed at being fed straight to
    //    a model. Free tier: 1000 credits/month; advanced search = 2
    //    credits per query. Best accuracy in practice, so it's the
    //    primary when the user has configured a key.
    //
    // 2. Brave Search API (PREF_BRAVE_SEARCH_API_KEY) — stable JSON,
    //    2000 queries/month on the free plan. Used as fallback when
    //    Tavily is absent or errors out.
    //
    // 3. Brave HTML scraping (no key) — free, unlimited, but regex
    //    parser is fragile and snippet quality is lower. Last resort.
    // ────────────────────────────────────────────────────────────────────

    private fun runWebSearch(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        if (query.isBlank()) return ToolResult("Missing 'query'", isError = true)
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)
        Log.i("AiToolRegistry", "web_search max=$maxResults")

        val tavilyKey = SecureApiKeys.getKey(
            helium314.keyboard.latin.settings.Settings.PREF_TAVILY_API_KEY
        )
        val braveKey = SecureApiKeys.getKey(
            helium314.keyboard.latin.settings.Settings.PREF_BRAVE_SEARCH_API_KEY
        )

        // 1. Tavily (primary)
        if (tavilyKey.isNotEmpty()) {
            try {
                val tav = tavilySearch(query, maxResults, tavilyKey)
                Log.i("AiToolRegistry", "web_search tavily results=${tav.results.size} answer=${tav.answer != null}")
                if (tav.results.isNotEmpty() || tav.answer != null) {
                    return formatTavilyResults(tav)
                }
            } catch (e: Exception) {
                Log.w("AiToolRegistry", "web_search tavily failed: ${e.message}; falling back")
            }
        }

        // 2. Brave API
        if (braveKey.isNotEmpty()) {
            try {
                val apiResults = braveSearchApi(query, maxResults, braveKey)
                Log.i("AiToolRegistry", "web_search brave-api results=${apiResults.size}")
                if (apiResults.isNotEmpty()) return formatSearchResults(apiResults)
            } catch (e: Exception) {
                Log.w("AiToolRegistry", "web_search brave-api failed: ${e.message}; falling back to HTML")
            }
        }

        // 3. Brave HTML
        return try {
            val htmlResults = braveSearchHtml(query, maxResults)
            Log.i("AiToolRegistry", "web_search brave-html results=${htmlResults.size}")
            if (htmlResults.isNotEmpty()) formatSearchResults(htmlResults)
            else ToolResult("No results for: $query")
        } catch (e: Exception) {
            Log.w("AiToolRegistry", "web_search brave-html failed: ${e.message}")
            ToolResult("web_search failed: ${e.message}", isError = true)
        }
    }

    private data class TavilyResponse(val answer: String?, val results: List<SearchResult>)

    /**
     * Call Tavily Search API (https://api.tavily.com/search). Uses
     * advanced search depth for higher-quality snippets. `include_answer`
     * asks Tavily to synthesize a short direct answer which we surface
     * above the per-result list when present.
     */
    private fun tavilySearch(query: String, max: Int, apiKey: String): TavilyResponse {
        // NB: include_answer is deliberately FALSE. Tavily's answer field is
        // a separate LLM synthesis that has been observed to hallucinate on
        // live/ongoing events — e.g. reporting a half-time score as the final
        // result and claiming a team "advanced" while the match is still in
        // progress. The raw snippets contain the correct live status, so we
        // force the calling model to read them instead of relying on Tavily's
        // pre-baked answer.
        val body = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("search_depth", "advanced")
            put("max_results", max)
            put("include_answer", false)
            put("include_raw_content", false)
        }.toString()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://api.tavily.com/search").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Deskdrop/1.0 (+web_search tool)")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300).orEmpty()
                } catch (_: Exception) { "" }
                throw RuntimeException("HTTP $code $errBody")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val answer = json.optString("answer").takeIf { it.isNotBlank() }
            val arr = json.optJSONArray("results")
            val out = mutableListOf<SearchResult>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    if (out.size >= max) break
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    val u = o.optString("url").trim()
                    val content = o.optString("content").trim()
                    if (title.isBlank() || u.isBlank()) continue
                    out.add(SearchResult(title = title, url = u, snippet = content.take(600)))
                }
            }
            return TavilyResponse(answer = answer, results = out)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun formatTavilyResults(tav: TavilyResponse): ToolResult {
        val sb = StringBuilder()
        sb.append(
            "Raw web snippets. Read them carefully before answering. " +
                "Do NOT assume scores/results are final — look for indicators " +
                "like 'LIVE', 'HT' (half-time), 'FT' (full-time), dates, and " +
                "kick-off times. If the match/event is still ongoing, say so.\n\n"
        )
        tav.results.forEachIndexed { i, r ->
            if (i > 0) sb.append("\n\n")
            sb.append(i + 1).append(". ").append(r.title)
            sb.append("\n   ").append(r.url)
            if (r.snippet.isNotBlank()) sb.append("\n   ").append(r.snippet)
        }
        return ToolResult(sb.toString())
    }

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private fun formatSearchResults(results: List<SearchResult>): ToolResult =
        ToolResult(
            results.mapIndexed { i, r ->
                "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
            }.joinToString("\n\n")
        )

    /**
     * Scrape https://search.brave.com/search?q=... — free, no key.
     * Brave uses Svelte with hashed class suffixes, so we match on the
     * stable fragments (`search-snippet-title`, `snippet-url`) which have
     * been stable across multiple frontend releases.
     */
    private fun braveSearchHtml(query: String, max: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://search.brave.com/search?q=$encoded&source=web"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val maxBytes = 400 * 1024
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            conn.inputStream.use { s ->
                while (total < maxBytes) {
                    val toRead = minOf(buf.size, maxBytes - total)
                    val n = s.read(buf, 0, toRead)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            val html = out.toString(Charsets.UTF_8.name())
            return parseBraveHtml(html, max)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Parse Brave Search HTML. Brave uses Svelte with build-hash class
     * suffixes, so we key off stable attributes instead of class names:
     *
     * 1. Classic web results live in `<div ... data-type="web" data-pos="N" …>`
     *    blocks. Inside each block we take the first external href, the
     *    `.title` text, and the `.snippet-content` text.
     *
     * 2. For sports / news widgets the page has no `data-type="web"` block.
     *    As a fallback we extract unique external anchors from the whole
     *    page, using the anchor's visible text as the title. This keeps
     *    queries like "bayern vs real madrid yesterday" from returning 0.
     */
    private fun parseBraveHtml(html: String, max: Int): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val seenUrls = HashSet<String>()

        // Phase 1: data-type="web" blocks
        val blockStarts = Regex("""<div[^>]*data-type="web"[^>]*>""")
            .findAll(html).map { it.range.first }.toList()
        for ((i, start) in blockStarts.withIndex()) {
            if (out.size >= max) break
            val end = if (i + 1 < blockStarts.size) blockStarts[i + 1] else (start + 6000).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val hrefMatch = Regex("""<a[^>]*href="(https?://[^"]+)"""").find(block) ?: continue
            val url = decodeHtmlEntities(hrefMatch.groupValues[1])
            if (isBraveInternalUrl(url) || !seenUrls.add(url)) continue
            val titleMatch = Regex("""<div[^>]*class="title[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(block)
                ?: Regex("""<a[^>]*class="[^"]*search-snippet-title[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(block)
            val title = titleMatch?.groupValues?.get(1)?.let { htmlToText(it) }.orEmpty()
            if (title.isBlank()) continue
            val descMatch = Regex("""<div[^>]*class="snippet-content[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(block)
            val desc = descMatch?.groupValues?.get(1)?.let { htmlToText(it).take(400) }.orEmpty()
            out.add(SearchResult(title = title, url = url, snippet = desc))
        }

        // Phase 2: fallback — unique external anchors (news widgets, sports cards, etc.)
        if (out.size < max) {
            val anchorRegex = Regex(
                """<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (m in anchorRegex.findAll(html)) {
                if (out.size >= max) break
                val url = decodeHtmlEntities(m.groupValues[1])
                if (isBraveInternalUrl(url) || !seenUrls.add(url)) continue
                val text = htmlToText(m.groupValues[2])
                if (text.length < 8) continue
                out.add(SearchResult(title = text.take(200), url = url, snippet = ""))
            }
        }
        return out
    }

    private fun isBraveInternalUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("://brave.com") ||
                u.contains("://search.brave.com") ||
                u.contains("://api.brave.com") ||
                u.contains("://community.brave.com") ||
                u.contains("://support.brave.com")
    }

    /**
     * Call Brave Search API. Returns parsed results. Throws on HTTP error
     * or malformed JSON so the caller can surface a diagnostic.
     */
    private fun braveSearchApi(query: String, max: Int, apiKey: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$max"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("X-Subscription-Token", apiKey)
                setRequestProperty("User-Agent", "Deskdrop/1.0 (+web_search tool)")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                } catch (_: Exception) { "" }
                throw RuntimeException("HTTP $code $errBody")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val web = json.optJSONObject("web") ?: return emptyList()
            val arr = web.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResult>()
            for (i in 0 until arr.length()) {
                if (out.size >= max) break
                val o = arr.optJSONObject(i) ?: continue
                val title = htmlToText(o.optString("title"))
                val u = o.optString("url")
                val desc = htmlToText(o.optString("description"))
                if (title.isBlank() || u.isBlank()) continue
                out.add(SearchResult(title = title, url = u, snippet = desc.take(400)))
            }
            return out
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")

    private fun htmlToText(s: String): String {
        var t = s
        t = Regex("<[^>]+>").replace(t, "")
        t = t.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
        return Regex("\\s+").replace(t, " ").trim()
    }

    // ────────────────────────────────────────────────────────────────────
    // Actions: set_timer / open_app
    // ────────────────────────────────────────────────────────────────────

    private fun runSetTimer(args: JSONObject): ToolResult {
        val seconds = args.optInt("seconds", -1)
        if (seconds <= 0 || seconds > 24 * 3600) {
            return ToolResult("'seconds' must be between 1 and 86400", isError = true)
        }
        val label = args.optString("label", "")
        val ctx = requireAppContext()
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(ctx.packageManager) == null) {
                return ToolResult("No Clock app available to handle timer", isError = true)
            }
            ctx.startActivity(intent)
            ToolResult("Timer set for ${formatDuration(seconds)}")
        } catch (e: Exception) {
            ToolResult("Failed to set timer: ${e.message}", isError = true)
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("${h}h")
        if (m > 0) parts.add("${m}m")
        if (s > 0 && h == 0) parts.add("${s}s")
        return parts.joinToString(" ")
    }

    private fun runOpenApp(args: JSONObject): ToolResult {
        val query = args.optString("app").trim()
        if (query.isBlank()) return ToolResult("Missing 'app'", isError = true)
        val ctx = requireAppContext()
        val pm = ctx.packageManager
        var intent: Intent? = pm.getLaunchIntentForPackage(query)
        var matchedLabel: String = query
        if (intent == null) {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = try { pm.queryIntentActivities(launcherIntent, 0) } catch (_: Exception) { emptyList() }
            val exact = apps.firstOrNull {
                it.loadLabel(pm).toString().equals(query, ignoreCase = true)
            }
            val partial = exact ?: apps.firstOrNull {
                it.loadLabel(pm).toString().contains(query, ignoreCase = true)
            }
            if (partial != null) {
                matchedLabel = partial.loadLabel(pm).toString()
                intent = pm.getLaunchIntentForPackage(partial.activityInfo.packageName)
            }
        }
        if (intent == null) return ToolResult("App not found: $query", isError = true)
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            ToolResult("Opened $matchedLabel")
        } catch (e: Exception) {
            ToolResult("Failed to open $query: ${e.message}", isError = true)
        }
    }
}
