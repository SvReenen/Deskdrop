package helium314.keyboard.latin.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

object SecureApiKeys {

    private const val FILE_NAME = "secure_api_keys"
    private var prefs: SharedPreferences? = null

    /** True if EncryptedSharedPreferences initialized successfully. False means
     *  plaintext fallback is active — API keys are NOT encrypted at rest. */
    @JvmStatic
    var isEncrypted: Boolean = false
        private set

    @JvmStatic
    fun init(context: Context) {
        if (prefs != null) return
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            isEncrypted = true
        } catch (e: Exception) {
            // Fallback: if encrypted prefs fail (rare, old devices), use regular prefs.
            // Loudly log this — users expect keys to be encrypted at rest, and silent
            // degradation would hide a real security regression.
            android.util.Log.e(
                "SecureApiKeys",
                "EncryptedSharedPreferences unavailable, falling back to PLAINTEXT prefs. " +
                    "API keys and MCP tokens on this device are NOT encrypted at rest.",
                e
            )
            prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // MCP bearer tokens — stored in the same encrypted file as API keys,
    // keyed by server id. Kept separate from getKey/setKey because tokens
    // have no whitelist of known pref names.
    // ────────────────────────────────────────────────────────────────────

    private fun mcpTokenPrefKey(serverId: String) = "mcp_token_$serverId"

    @JvmStatic
    fun getMcpToken(serverId: String): String =
        (prefs?.getString(mcpTokenPrefKey(serverId), "") ?: "").trim()

    @JvmStatic
    fun setMcpToken(serverId: String, value: String) {
        val p = prefs
        if (p == null) {
            android.util.Log.e("SecureApiKeys", "setMcpToken($serverId) called before init() - value will NOT persist")
            return
        }
        p.edit().putString(mcpTokenPrefKey(serverId), value.trim()).apply()
    }

    @JvmStatic
    fun removeMcpToken(serverId: String) {
        prefs?.edit()?.remove(mcpTokenPrefKey(serverId))?.apply()
    }

    @JvmStatic
    fun getKey(prefKey: String): String {
        val default = when (prefKey) {
            Settings.PREF_GEMINI_API_KEY -> Defaults.PREF_GEMINI_API_KEY
            Settings.PREF_GROQ_API_KEY -> Defaults.PREF_GROQ_API_KEY
            Settings.PREF_OPENROUTER_API_KEY -> Defaults.PREF_OPENROUTER_API_KEY
            Settings.PREF_OPENAI_COMPAT_API_KEY -> Defaults.PREF_OPENAI_COMPAT_API_KEY
            Settings.PREF_ANTHROPIC_API_KEY -> Defaults.PREF_ANTHROPIC_API_KEY
            Settings.PREF_OPENAI_API_KEY -> Defaults.PREF_OPENAI_API_KEY
            Settings.PREF_BRAVE_SEARCH_API_KEY -> Defaults.PREF_BRAVE_SEARCH_API_KEY
            Settings.PREF_TAVILY_API_KEY -> Defaults.PREF_TAVILY_API_KEY
            else -> ""
        }
        // Defensive trim: API keys must never contain leading/trailing whitespace.
        // Pasted keys often carry a stray newline or space that would otherwise
        // cause silent 400 "invalid API key" failures from the provider.
        return (prefs?.getString(prefKey, default) ?: default).trim()
    }

    @JvmStatic
    fun setKey(prefKey: String, value: String) {
        val p = prefs
        if (p == null) {
            android.util.Log.e("SecureApiKeys", "setKey($prefKey) called before init() - value will NOT persist")
            return
        }
        // Always store the trimmed form so a paste with trailing whitespace can't
        // poison future reads.
        p.edit().putString(prefKey, value.trim()).apply()
    }

    /**
     * Migrate plain text API keys from regular SharedPreferences to encrypted storage.
     * Clears the plain text keys after migration.
     */
    @JvmStatic
    fun migrateFromPlainPrefs(plainPrefs: SharedPreferences) {
        val keys = listOf(
            Settings.PREF_GEMINI_API_KEY,
            Settings.PREF_GROQ_API_KEY,
            Settings.PREF_OPENROUTER_API_KEY,
            Settings.PREF_OPENAI_COMPAT_API_KEY,
            Settings.PREF_ANTHROPIC_API_KEY,
            Settings.PREF_OPENAI_API_KEY,
            Settings.PREF_BRAVE_SEARCH_API_KEY,
            Settings.PREF_TAVILY_API_KEY
        )
        val securePrefs = prefs ?: return
        var migrated = false
        for (key in keys) {
            val plainValue = plainPrefs.getString(key, "") ?: ""
            if (plainValue.isNotBlank() && securePrefs.getString(key, "").isNullOrBlank()) {
                securePrefs.edit().putString(key, plainValue).apply()
                plainPrefs.edit().remove(key).apply()
                migrated = true
            }
        }
    }
}
