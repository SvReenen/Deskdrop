// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.content.Intent
import android.net.Uri
import helium314.keyboard.latin.ai.AiToolRegistry.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Open the SMS app with a recipient and message pre-filled. Uses ACTION_SENDTO
 * with an smsto: URI so no SMS permission is needed — the user presses send.
 */
class SendSmsTool : AiTool {

    override val name = "send_sms"

    override val description =
        "Open the SMS/messaging app with a message ready to send. The user must press send " +
        "themselves. Provide `phone_number` for the recipient and `message` for the text."

    override val gate = ToolGate.ACTIONS

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("phone_number", JSONObject().apply {
                put("type", "string")
                put("description", "Recipient phone number, e.g. '+31612345678'.")
            })
            put("message", JSONObject().apply {
                put("type", "string")
                put("description", "The text message to send.")
            })
        })
        put("required", JSONArray().put("phone_number").put("message"))
    }

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val number = (
            args.optString("phone_number").ifBlank {
                args.optString("number").ifBlank {
                    args.optString("phone").ifBlank {
                        args.optString("recipient")
                    }
                }
            }
        ).trim()

        val message = (
            args.optString("message").ifBlank {
                args.optString("text").ifBlank {
                    args.optString("body").ifBlank {
                        args.optString("content")
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
        if (message.isBlank()) {
            return ToolResult(
                "Missing required parameter 'message'. Received keys: ${args.keys().asSequence().toList()}",
                isError = true
            )
        }

        return try {
            val uri = Uri.parse("smsto:${Uri.encode(number)}")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.appContext.startActivity(intent)
            ToolResult("Opened SMS to $number with message: $message")
        } catch (e: Exception) {
            ToolResult("Failed to open SMS app: ${e.message}", isError = true)
        }
    }
}
