// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.DeviceProtectedUtils

/**
 * Manages dynamic app shortcuts (long-press launcher icon).
 * Static shortcuts (new chat, dictate) are declared in res/xml/shortcuts.xml.
 * Dynamic shortcuts are rebuilt when cloud presets change.
 */
object DeskdropShortcutManager {

    fun rebuildDynamicShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT < 25) return
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        val shortcuts = mutableListOf<ShortcutInfoCompat>()

        // "Last chat" shortcut — always present
        shortcuts.add(
            ShortcutInfoCompat.Builder(context, "last_chat")
                .setShortLabel(context.getString(R.string.shortcut_last_chat))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat))
                .setIntent(
                    Intent(Intent.ACTION_VIEW).apply {
                        setClass(context, ConversationActivity::class.java)
                        putExtra(ConversationActivity.EXTRA_ACTION, ConversationActivity.ACTION_LAST_CHAT)
                    }
                )
                .setRank(0)
                .build()
        )

        // Per-preset shortcuts (max 3)
        val presets = loadCloudPresets(prefs)
        presets.take(3).forEachIndexed { idx, preset ->
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, "preset_$idx")
                    .setShortLabel(preset.displayName.take(25))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat))
                    .setIntent(
                        Intent(Intent.ACTION_VIEW).apply {
                            setClass(context, ConversationActivity::class.java)
                            putExtra(ConversationActivity.EXTRA_ACTION, ConversationActivity.ACTION_PRESET)
                            putExtra(ConversationActivity.EXTRA_PRESET_INDEX, idx)
                        }
                    )
                    .setRank(idx + 1)
                    .build()
            )
        }

        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: Exception) {
            // ShortcutManager can throw on some OEM ROMs
        }
    }
}
