// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.content.Intent
import android.net.Uri
import helium314.keyboard.latin.ai.AiToolRegistry.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Open turn-by-turn navigation to a destination. Uses the standard `google.navigation:`
 * URI scheme which works with Google Maps, Waze, and most Android navigation apps.
 * Falls back to a `geo:` query if the user just wants to find a place on the map.
 */
class NavigationTool : AiTool {

    override val name = "navigate"

    override val description =
        "Open navigation to a destination. The device's default maps/navigation app will launch " +
        "with directions. Use `destination` for an address or place name (e.g. 'Amsterdam Centraal', " +
        "'Kalverstraat 1, Amsterdam'). Set `mode` to 'd' (driving, default), 'w' (walking), " +
        "'b' (bicycling), or 'l' (two-wheeler)."

    override val gate = ToolGate.ACTIONS

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("destination", JSONObject().apply {
                put("type", "string")
                put("description", "Address, place name, or coordinates (lat,lng). Examples: 'Albert Heijn Delft', 'Schiphol Airport', '52.3676,4.9041'.")
            })
            put("mode", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().put("d").put("w").put("b").put("l"))
                put("description", "Travel mode: 'd' = driving (default), 'w' = walking, 'b' = bicycling, 'l' = two-wheeler.")
            })
        })
        put("required", JSONArray().put("destination"))
    }

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val destination = (
            args.optString("destination").ifBlank {
                args.optString("address").ifBlank {
                    args.optString("location").ifBlank {
                        args.optString("query")
                    }
                }
            }
        ).trim()

        if (destination.isBlank()) {
            return ToolResult(
                "Missing required parameter 'destination'. Received keys: ${args.keys().asSequence().toList()}",
                isError = true
            )
        }

        val mode = args.optString("mode", "d").lowercase().let {
            if (it in listOf("d", "w", "b", "l")) it else "d"
        }

        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode")
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Check if any app can handle navigation
            if (intent.resolveActivity(ctx.appContext.packageManager) != null) {
                ctx.appContext.startActivity(intent)
                ToolResult("Navigation started to: $destination")
            } else {
                // Fallback to geo: URI which more apps support
                val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
                val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (geoIntent.resolveActivity(ctx.appContext.packageManager) != null) {
                    ctx.appContext.startActivity(geoIntent)
                    ToolResult("Opened map for: $destination")
                } else {
                    ToolResult("No navigation or maps app found on this device.", isError = true)
                }
            }
        } catch (e: Exception) {
            ToolResult("Failed to start navigation: ${e.message}", isError = true)
        }
    }
}
