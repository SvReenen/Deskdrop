// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.content.Intent
import android.net.Uri
import helium314.keyboard.latin.ai.AiToolRegistry.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Open the phone dialer with a number pre-filled. Uses ACTION_DIAL so no
 * CALL_PHONE permission is needed — the user still has to press the call button.
 */
class PhoneCallTool : AiTool {

    override val name = "phone_call"

    override val description =
        "Open the phone dialer with a number ready to call. The user must press the call button " +
        "themselves. Use `phone_number` for the number to dial (e.g. '+31612345678', '0612345678')."

    override val gate = ToolGate.ACTIONS

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("phone_number", JSONObject().apply {
                put("type", "string")
                put("description", "The phone number to dial, e.g. '+31612345678' or '0612345678'.")
            })
        })
        put("required", JSONArray().put("phone_number"))
    }

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val number = (
            args.optString("phone_number").ifBlank {
                args.optString("number").ifBlank {
                    args.optString("phone").ifBlank {
                        args.optString("tel")
                    }
                }
            }
        ).trim()

        if (number.isBlank()) {
            return ToolResult(
                "Missing required parameter 'phone_number'. Received keys: ${args.keys().asSequence().toList()}",
                isError = true
            )
        }
        val cleaned = number.replace(Regex("[^+0-9()\\- ]"), "")
        if (cleaned.length < 3 || !cleaned.matches(Regex("^\\+?[0-9()\\- ]{3,20}$"))) {
            return ToolResult("Invalid phone number format: $number", isError = true)
        }

        return try {
            val uri = Uri.parse("tel:${Uri.encode(number)}")
            val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.appContext.startActivity(intent)
            ToolResult("Opened dialer with number: $number")
        } catch (e: Exception) {
            ToolResult("Failed to open dialer: ${e.message}", isError = true)
        }
    }
}
