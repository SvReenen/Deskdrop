// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens for UDP beacons from the Deskdrop desktop app on the local network.
 * The desktop broadcasts on port 5392 every 5 seconds.
 */
object DiscoveryListener {

    private const val TAG = "DiscoveryListener"
    private const val BEACON_PORT = 5392
    private const val TIMEOUT_MS = 8000

    data class DiscoveredServer(
        val lanIps: List<String>,
        val tailscaleIp: String,
        val port: Int,
        val hostname: String
    )

    /** Listen for a single beacon. Returns null if no beacon received within timeout. */
    suspend fun listenOnce(): DiscoveredServer? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(BEACON_PORT)
            socket.soTimeout = TIMEOUT_MS
            socket.reuseAddress = true

            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)

            val json = String(packet.data, 0, packet.length)
            val obj = JSONObject(json)

            if (obj.optString("service") != "deskdrop") return@withContext null

            val lanIps = mutableListOf<String>()
            val lanArr = obj.optJSONArray("lan")
            if (lanArr != null) {
                for (i in 0 until lanArr.length()) {
                    lanArr.optString(i)?.let { if (it.isNotBlank()) lanIps.add(it) }
                }
            }

            DiscoveredServer(
                lanIps = lanIps,
                tailscaleIp = obj.optString("tailscale", ""),
                port = obj.optInt("port", 5391),
                hostname = obj.optString("hostname", "")
            )
        } catch (e: Exception) {
            Log.d(TAG, "No beacon received: ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
