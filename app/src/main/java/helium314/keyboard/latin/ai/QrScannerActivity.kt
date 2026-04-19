// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class QrScannerActivity : ComponentActivity() {

    companion object {
        const val RESULT_QR_DATA = "qr_data"
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            try {
                val obj = JSONObject(result.contents)
                if (obj.has("token") && obj.has("port")) {
                    val resultIntent = Intent()
                    resultIntent.putExtra(RESULT_QR_DATA, result.contents)
                    setResult(RESULT_OK, resultIntent)
                }
            } catch (_: Exception) {}
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the QR code from Deskdrop desktop")
            setBeepEnabled(false)
            setOrientationLocked(true)
            setCameraId(0)
        }
        scanLauncher.launch(options)
    }
}
