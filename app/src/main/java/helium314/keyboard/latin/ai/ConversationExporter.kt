// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a [ConversationStore.StoredChat] to markdown or JSON and hands it
 * off to the system share sheet via the existing FileProvider.
 *
 * Files are written to `cacheDir/conversation_exports/` and get reused
 * between exports of the same chat (overwritten), so the cache doesn't
 * inflate. The FileProvider path entry is declared in
 * `res/xml/gesture_data_path.xml` as `conversation_exports`.
 */
object ConversationExporter {

    private const val DIR_NAME = "conversation_exports"

    /** Produce a self-contained markdown view of the chat. */
    fun toMarkdown(chat: ConversationStore.StoredChat): String {
        val sb = StringBuilder()
        sb.append("# ").append(chat.title).append('\n')
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("_Model: `").append(chat.model).append("`_  \n")
        sb.append("_Created: ").append(fmt.format(Date(chat.createdAt))).append("_  \n")
        sb.append("_Updated: ").append(fmt.format(Date(chat.updatedAt))).append("_\n\n")
        if (chat.systemPrompt.isNotBlank()) {
            sb.append("## System\n\n")
            sb.append(chat.systemPrompt.trim()).append("\n\n")
        }
        for (m in chat.messages) {
            val heading = when (m.role) {
                "user" -> "## User"
                "assistant" -> "## Assistant" +
                    if (m.modelLabel.isNotBlank()) " (${m.modelLabel})" else ""
                "system" -> "## System"
                "tool" -> "## Tool"
                else -> "## ${m.role}"
            }
            sb.append(heading).append('\n').append('\n')
            if (m.attachments.isNotEmpty()) {
                for (a in m.attachments) {
                    sb.append("> _attachment: ").append(File(a.path).name)
                        .append(" (").append(a.mimeType).append(")_\n")
                }
                sb.append('\n')
            }
            sb.append(m.content.trimEnd()).append("\n\n")
        }
        return sb.toString()
    }

    /**
     * Produce a full-fidelity JSON export. Attachment paths are rewritten
     * to **relative** form (`attachments/<filename>`) so the JSON makes
     * sense inside a ZIP backup alongside a sibling `attachments/` folder.
     * Absolute device paths are deliberately dropped because they are
     * useless on any other device or after a reinstall.
     */
    fun toJson(chat: ConversationStore.StoredChat): String {
        val msgs = JSONArray()
        for (m in chat.messages) {
            val atts = JSONArray()
            for (a in m.attachments) {
                val fileName = File(a.path).name
                atts.put(JSONObject().apply {
                    put("path", "attachments/$fileName")
                    put("fileName", fileName)
                    put("mimeType", a.mimeType)
                })
            }
            msgs.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
                put("modelLabel", m.modelLabel)
                put("isError", m.isError)
                if (atts.length() > 0) put("attachments", atts)
            })
        }
        val obj = JSONObject().apply {
            put("schemaVersion", 1)
            put("id", chat.id)
            put("title", chat.title)
            put("model", chat.model)
            put("createdAt", chat.createdAt)
            put("updatedAt", chat.updatedAt)
            put("pinned", chat.pinned)
            put("systemPrompt", chat.systemPrompt)
            put("messages", msgs)
        }
        return obj.toString(2)
    }

    /**
     * Write a self-contained `.zip` backup of [chat] to [outFile]. Layout:
     * ```
     * conversation.json       // relative-path JSON (see [toJson])
     * attachments/<name>      // copied bytes of each referenced file
     * ```
     * Attachments whose source file no longer exists on disk are skipped
     * silently — the JSON still references them, but the ZIP won't have
     * the bytes. This matches the "best-effort backup" semantics.
     */
    private fun writeBackupZip(chat: ConversationStore.StoredChat, outFile: File) {
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            // 1. JSON index
            zip.putNextEntry(ZipEntry("conversation.json"))
            zip.write(toJson(chat).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 2. Attachments — de-duplicate by filename since several messages
            //    may share the same physical file (e.g. re-sent image).
            val seen = HashSet<String>()
            for (m in chat.messages) {
                for (a in m.attachments) {
                    val src = File(a.path)
                    val name = src.name
                    if (!seen.add(name)) continue
                    if (!src.exists()) continue
                    zip.putNextEntry(ZipEntry("attachments/$name"))
                    src.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Sanitize a chat title into a safe filename stem. */
    private fun slugify(title: String): String {
        val cleaned = title.trim().replace(Regex("[^A-Za-z0-9-_ ]"), "")
            .replace(Regex("\\s+"), "_")
            .take(48)
        return cleaned.ifBlank { "conversation" }
    }

    private fun exportDir(context: Context): File {
        val d = File(context.cacheDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * Write the chat to cache and launch the system share sheet.
     *
     * Two formats:
     *  - `"md"` — human-readable markdown, also passed as EXTRA_TEXT so it
     *    works with apps that ignore the stream (e.g. Messages).
     *  - `"zip"` — self-contained backup (JSON + `attachments/` folder).
     *    Cross-device portable, intended for real backups or re-import.
     *
     * Returns true on success.
     */
    fun share(context: Context, chat: ConversationStore.StoredChat, format: String): Boolean {
        return try {
            val slug = slugify(chat.title)
            val file: File
            val mime: String
            var extraText: String? = null
            when (format) {
                "md" -> {
                    val content = toMarkdown(chat)
                    file = File(exportDir(context), "$slug.md")
                    file.writeText(content)
                    mime = "text/markdown"
                    extraText = content
                }
                "zip" -> {
                    file = File(exportDir(context), "$slug.zip")
                    if (file.exists()) file.delete()
                    writeBackupZip(chat, file)
                    mime = "application/zip"
                }
                else -> return false
            }
            val authority = context.getString(
                helium314.keyboard.latin.R.string.gesture_data_provider_authority
            )
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, chat.title)
                if (extraText != null) putExtra(Intent.EXTRA_TEXT, extraText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Export conversation").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.e("ConversationExporter", "share failed", e)
            false
        }
    }
}
