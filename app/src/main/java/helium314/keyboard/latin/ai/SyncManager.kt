// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates two-way sync between the Android app and the Deskdrop desktop.
 *
 * Sync strategy:
 * 1. Pull: fetch all conversations/messages updated since last sync from desktop
 * 2. Merge: upsert pulled data into local ConversationStore
 * 3. Push: send all local conversations updated since last sync to desktop
 * 4. Update lastSyncTimestamp
 */
object SyncManager {

    private const val TAG = "SyncManager"
    private const val PREF_LAST_SYNC = "sync_last_timestamp"

    data class SyncStatus(
        val success: Boolean,
        val pulled: Int = 0,
        val pushed: Int = 0,
        val error: String? = null
    )

    suspend fun sync(context: Context, serverUrl: String, token: String): SyncStatus =
        withContext(Dispatchers.IO) {
            try {
                val prefs = syncPrefs(context)
                val lastSync = prefs.getLong(PREF_LAST_SYNC, 0L)

                // 1. Pull from desktop
                val pullResult = SyncClient.pull(serverUrl, token, lastSync)
                    ?: return@withContext SyncStatus(false, error = "Kan desktop niet bereiken")

                // 2. Merge pulled conversations into local store
                var pullCount = 0
                for (remoteChat in pullResult.conversations) {
                    val localChat = ConversationStore.load(context, remoteChat.id)
                    if (localChat == null) {
                        // Nieuw gesprek van desktop
                        ConversationStore.save(context, remoteChat)
                        pullCount++
                    } else if (remoteChat.updatedAt > localChat.updatedAt) {
                        // Desktop versie is nieuwer: merge messages
                        val mergedMessages = mergeMessages(localChat.messages, remoteChat.messages)
                        ConversationStore.save(context, remoteChat.copy(messages = mergedMessages))
                        pullCount++
                    }
                }

                // 3. Push local changes to desktop
                val localChats = ConversationStore.listAll(context)
                val chatsToSync = localChats
                    .filter { it.updatedAt > lastSync }
                    .mapNotNull { ConversationStore.load(context, it.id) }

                val pushOk = if (chatsToSync.isNotEmpty()) {
                    SyncClient.push(serverUrl, token, chatsToSync)
                } else true

                if (!pushOk) {
                    return@withContext SyncStatus(false, pulled = pullCount, error = "Push mislukt")
                }

                // 4. Update timestamp
                prefs.edit().putLong(PREF_LAST_SYNC, pullResult.timestamp).apply()

                Log.i(TAG, "Sync done: pulled=$pullCount, pushed=${chatsToSync.size}")
                SyncStatus(true, pulled = pullCount, pushed = chatsToSync.size)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                SyncStatus(false, error = e.message)
            }
        }

    /** Merge two message lists by UUID deduplication. */
    private fun mergeMessages(
        local: List<ConversationStore.StoredMessage>,
        remote: List<ConversationStore.StoredMessage>
    ): List<ConversationStore.StoredMessage> {
        val byId = linkedMapOf<String, ConversationStore.StoredMessage>()

        // Local first
        for (msg in local) byId[msg.id] = msg

        // Remote overwrites (desktop is preferred host)
        for (msg in remote) byId[msg.id] = msg

        return byId.values.sortedBy { it.sortOrder }
    }

    /** Save pairing info from QR code or discovery. */
    fun savePairing(context: Context, token: String, lanIps: List<String>, tailscaleIp: String, port: Int, hostname: String) {
        val prefs = context.getSharedPreferences("deskdrop_sync", Context.MODE_PRIVATE)
        val mainPrefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(context)
        mainPrefs.edit()
            .putString(helium314.keyboard.latin.settings.Settings.PREF_SYNC_TOKEN, token)
            .putBoolean(helium314.keyboard.latin.settings.Settings.PREF_SYNC_ENABLED, true)
            .apply()
        prefs.edit()
            .putString("paired_lan_ips", lanIps.joinToString(","))
            .putString("paired_tailscale_ip", tailscaleIp)
            .putInt("paired_port", port)
            .putString("paired_hostname", hostname)
            .apply()
    }

    /** Try to find a working server URL from stored pairing info. */
    suspend fun resolveServerUrl(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = syncPrefs(context)
        val mainPrefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(context)
        val token = mainPrefs.getString(helium314.keyboard.latin.settings.Settings.PREF_SYNC_TOKEN, "") ?: ""
        if (token.isBlank()) return@withContext null

        val port = prefs.getInt("paired_port", 5391)
        val lanIps = (prefs.getString("paired_lan_ips", "") ?: "").split(",").filter { it.isNotBlank() }
        val tailscaleIp = prefs.getString("paired_tailscale_ip", "") ?: ""
        val configuredUrl = mainPrefs.getString(helium314.keyboard.latin.settings.Settings.PREF_SYNC_SERVER_URL, "") ?: ""

        // Try in order: configured URL, LAN IPs, Tailscale IP
        val urls = mutableListOf<String>()
        if (configuredUrl.isNotBlank()) urls.add(configuredUrl)
        for (ip in lanIps) urls.add("http://$ip:$port")
        if (tailscaleIp.isNotBlank()) urls.add("http://$tailscaleIp:$port")

        for (url in urls.distinct()) {
            if (SyncClient.testConnection(url, token)) {
                // Save working URL
                mainPrefs.edit()
                    .putString(helium314.keyboard.latin.settings.Settings.PREF_SYNC_SERVER_URL, url)
                    .apply()
                return@withContext url
            }
        }
        null
    }

    fun getLastSyncTime(context: Context): Long {
        return syncPrefs(context).getLong(PREF_LAST_SYNC, 0L)
    }

    fun isPaired(context: Context): Boolean {
        val prefs = syncPrefs(context)
        return prefs.getString("paired_hostname", "")?.isNotBlank() == true
    }

    private fun syncPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("deskdrop_sync", Context.MODE_PRIVATE)
    }
}
