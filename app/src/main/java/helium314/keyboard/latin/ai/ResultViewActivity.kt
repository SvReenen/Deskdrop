// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import helium314.keyboard.latin.utils.Theme

class ResultViewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialText = intent.getStringExtra("result") ?: ""
        val streaming = intent.getBooleanExtra("streaming", false)

        enableEdgeToEdge()
        setContent {
            Theme {
                AiResultViewContent(
                    initialText = initialText,
                    streaming = streaming,
                    onCancelStream = {
                        AiCancelRegistry.cancel()
                        finish()
                    },
                    onClose = {
                        AiRetryRegistry.clear()
                        finish()
                    },
                    onNewPrompt = {
                        AiRetryRegistry.clear()
                        PendingInsertBridge.writeReopenFlag(this@ResultViewActivity)
                        finish()
                    },
                    onInsert = { text ->
                        AiRetryRegistry.clear()
                        PendingInsertBridge.writeInsert(this@ResultViewActivity, text)
                        finish()
                    }
                )
            }
        }
    }
}
