// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.ai.DeskdropShortcutManager
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // The :chat process hosts ConversationActivity, ResultViewActivity, and
        // ProcessTextActivity. It only needs SecureApiKeys (for API keys) and
        // DebugFlags (for crash handler). Skip all IME-specific initialization
        // to keep the chat process lightweight and avoid singletons that assume
        // they live in the IME process.
        val processName = getProcessName()
        if (processName != null && processName.endsWith(":chat")) {
            DebugFlags.init(this)
            helium314.keyboard.latin.ai.SecureApiKeys.init(this)
            return
        }

        DebugFlags.init(this)
        Settings.init(this)
        SubtypeSettings.init(this)
        RichInputMethodManager.init(this)
        // Init encrypted API key storage early so the Settings UI can read/write
        // even if the IME service hasn't been started yet. Without this, edits in
        // AI Settings silently no-op when prefs == null.
        helium314.keyboard.latin.ai.SecureApiKeys.init(this)

        AppUpgrade.checkVersionUpgrade(this)
        AppUpgrade.transferOldPinnedClips(this) // todo: remove in a few months, maybe mid 2026
        app = this
        Defaults.initDynamicDefaults(this)
        LayoutUtilsCustom.removeMissingLayouts(this) // only after version upgrade
        SupportedEmojis.load(this)

        // Build dynamic app shortcuts (last chat, presets) and refresh when presets change
        DeskdropShortcutManager.rebuildDynamicShortcuts(this)

        DeviceProtectedUtils.getSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener { _, key ->
                if (key == Settings.PREF_AI_CLOUD_PRESETS) {
                    DeskdropShortcutManager.rebuildDynamicShortcuts(this@App)
                }
            }

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        Log.i(
            "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                packageInfo.versionCode
            }) on Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        )
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}
