// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.ai.AiToolRegistry.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Look up contacts by name. Returns matching names and phone numbers so
 * the AI can chain into [PhoneCallTool] or [SendSmsTool].
 * Requires READ_CONTACTS permission.
 */
class ContactLookupTool : AiTool {

    override val name = "contact_lookup"

    override val description =
        "Search the user's contacts by name and return matching names, phone numbers and email " +
        "addresses. Use this when the user refers to a person by name and you need their phone " +
        "number or email. Returns up to 5 matches. After getting the number, use phone_call or " +
        "send_sms to act on it."

    override val gate = ToolGate.ACTIONS

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "The contact name to search for (partial match). E.g. 'Royce', 'mama', 'Dr. Jansen'.")
            })
        })
        put("required", JSONArray().put("name"))
    }

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = (
            args.optString("name").ifBlank {
                args.optString("query").ifBlank {
                    args.optString("contact")
                }
            }
        ).trim()

        if (query.isBlank()) {
            return ToolResult(
                "Missing required parameter 'name'. Received keys: ${args.keys().asSequence().toList()}",
                isError = true
            )
        }

        // Check READ_CONTACTS permission
        val granted = ContextCompat.checkSelfPermission(
            ctx.appContext, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return openAppSettings(ctx,
                "Contacts permission not granted. I've opened settings for you. Enable 'Contacts' and try again.")
        }

        return try {
            val results = searchContacts(ctx, query)
            if (results.isEmpty()) {
                ToolResult("No contacts found matching '$query'.")
            } else {
                val sb = StringBuilder("Found ${results.size} contact(s):\n")
                for (c in results) {
                    sb.append("\n- ${c.name}")
                    if (c.phones.isNotEmpty()) sb.append("\n  Phone: ${c.phones.joinToString(", ")}")
                    if (c.emails.isNotEmpty()) sb.append("\n  Email: ${c.emails.joinToString(", ")}")
                }
                ToolResult(sb.toString())
            }
        } catch (e: Exception) {
            ToolResult("Failed to search contacts: ${e.message}", isError = true)
        }
    }

    private data class ContactResult(
        val name: String,
        val phones: List<String>,
        val emails: List<String>
    )

    private fun searchContacts(ctx: ToolContext, query: String): List<ContactResult> {
        val resolver = ctx.appContext.contentResolver
        val contacts = mutableListOf<ContactResult>()

        // Search by display name
        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        ) ?: return emptyList()

        cursor.use {
            var count = 0
            while (it.moveToNext() && count < 3) {
                val id = it.getString(0)
                val name = it.getString(1) ?: continue

                val phones = getPhones(ctx, id).take(2)
                val emails = getEmails(ctx, id).take(1)
                contacts.add(ContactResult(name, phones, emails))
                count++
            }
        }
        return contacts
    }

    private fun getPhones(ctx: ToolContext, contactId: String): List<String> {
        val phones = mutableListOf<String>()
        val cursor = ctx.appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        ) ?: return emptyList()
        cursor.use {
            while (it.moveToNext()) {
                val number = it.getString(0)?.trim()
                if (!number.isNullOrBlank() && number !in phones) phones.add(number)
            }
        }
        return phones
    }

    private fun getEmails(ctx: ToolContext, contactId: String): List<String> {
        val emails = mutableListOf<String>()
        val cursor = ctx.appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        ) ?: return emptyList()
        cursor.use {
            while (it.moveToNext()) {
                val email = it.getString(0)?.trim()
                if (!email.isNullOrBlank() && email !in emails) emails.add(email)
            }
        }
        return emails
    }

    private fun openAppSettings(ctx: ToolContext, message: String): ToolResult {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.appContext.startActivity(intent)
        } catch (_: Exception) { }
        return ToolResult(message, isError = true)
    }
}
