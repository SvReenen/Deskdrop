// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.forEach
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.ReminderStore
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.Locale

fun createToolbarKey(context: Context, key: ToolbarKey): ImageButton {
    val button = ImageButton(context, null, R.attr.suggestionWordStyle)
    button.scaleType = ImageView.ScaleType.CENTER
    button.tag = key
    button.contentDescription = key.name.lowercase().getStringResourceOrName("", context)
    setToolbarButtonActivatedState(button)
    button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
    return button
}

/**
 * Drawable wrapper that renders [base] plus a small coloured dot in the
 * top-right corner when [showDot] is true. Used to badge the
 * AI_CONVERSATION toolbar button when there are unread reminders.
 */
class DotBadgeDrawable(
    private val base: Drawable,
    dotColor: Int,
    private val dotRadiusPx: Float,
    private val marginPx: Float
) : Drawable() {

    var showDot: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }

    override fun draw(canvas: Canvas) {
        base.draw(canvas)
        if (!showDot) return
        val b = bounds
        val cx = b.right - dotRadiusPx - marginPx
        val cy = b.top + dotRadiusPx + marginPx
        canvas.drawCircle(cx, cy, dotRadiusPx, paint)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        base.bounds = bounds
    }

    override fun getIntrinsicWidth() = base.intrinsicWidth
    override fun getIntrinsicHeight() = base.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        base.alpha = alpha
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        base.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Badge the AI_CONVERSATION button with a small orange dot in the top-right
 * corner when there are unread reminders. First call wraps the button's
 * drawable in a [DotBadgeDrawable]; subsequent calls just flip the dot
 * visibility so the wrapper persists across refreshes.
 */
fun applyReminderAccentIfNeeded(context: Context, button: ImageButton) {
    if (button.tag != AI_CONVERSATION) return
    val current = button.drawable ?: return
    try {
        val wrapper: DotBadgeDrawable = if (current is DotBadgeDrawable) {
            current
        } else {
            val density = context.resources.displayMetrics.density
            val w = DotBadgeDrawable(
                base = current,
                dotColor = ContextCompat.getColor(context, R.color.reminder_accent),
                dotRadiusPx = 3.5f * density,
                marginPx = 1f * density
            )
            button.setImageDrawable(w)
            w
        }
        wrapper.showDot = ReminderStore.hasUnread(context)
        button.invalidate()
    } catch (_: Exception) { /* best-effort badge */ }
}

/**
 * Badge AI slot keys with a red dot when cloud fallback is active,
 * indicating the configured local model has been replaced by a cloud model.
 */
fun applyCloudFallbackBadge(context: Context, button: ImageButton) {
    val tag = button.tag as? ToolbarKey ?: return
    if (tag !in listOf(AI_SLOT_1, AI_SLOT_2, AI_SLOT_3, AI_SLOT_4, AI_ASSIST, AI_VOICE)) return
    val current = button.drawable ?: return
    try {
        val prefs = context.prefs()
        val fallbackActive = helium314.keyboard.latin.ai.AiServiceSync.isCloudFallbackActive(prefs)
        val wrapper: DotBadgeDrawable = if (current is DotBadgeDrawable) {
            current
        } else {
            val density = context.resources.displayMetrics.density
            val w = DotBadgeDrawable(
                base = current,
                dotColor = 0xFFE53935.toInt(), // red
                dotRadiusPx = 3.5f * density,
                marginPx = 1f * density
            )
            button.setImageDrawable(w)
            w
        }
        wrapper.showDot = fallbackActive
        button.invalidate()
    } catch (_: Exception) { /* best-effort badge */ }
}

fun setToolbarButtonsActivatedStateOnPrefChange(buttonsGroup: ViewGroup, key: String?) {
    // settings need to be updated when buttons change
    if (key != Settings.PREF_AUTO_CORRECTION
        && key != Settings.PREF_ALWAYS_INCOGNITO_MODE
        && key?.startsWith(Settings.PREF_ONE_HANDED_MODE_PREFIX) == false
        && key?.startsWith("ai_slot_") == false)
        return

    GlobalScope.launch {
        delay(10) // need to wait until SettingsValues are reloaded
        buttonsGroup.forEach { if (it is ImageButton) setToolbarButtonActivatedState(it) }
    }
}

private fun setToolbarButtonActivatedState(button: ImageButton) {
    button.isActivated = when (button.tag) {
        INCOGNITO -> button.context.prefs().getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE)
        ONE_HANDED -> Settings.getValues().mOneHandedModeEnabled
        SPLIT -> Settings.getValues().mIsSplitKeyboardEnabled
        AUTOCORRECT -> Settings.getValues().mAutoCorrectionEnabledPerUserSettings
        AI_SLOT_1, AI_SLOT_2, AI_SLOT_3, AI_SLOT_4 -> {
            val slotNum = (button.tag as ToolbarKey).name.last().digitToInt()
            val model = button.context.prefs().getString("ai_slot_${slotNum}_model", "")
            !model.isNullOrEmpty()
        }
        else -> true
    }
}

fun getCodeForToolbarKey(key: ToolbarKey) = Settings.getInstance().getCustomToolbarKeyCode(key) ?: when (key) {
    VOICE -> KeyCode.VOICE_INPUT
    CLIPBOARD -> KeyCode.CLIPBOARD
    NUMPAD -> KeyCode.NUMPAD
    UNDO -> KeyCode.UNDO
    REDO -> KeyCode.REDO
    SETTINGS -> KeyCode.SETTINGS
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_ALL
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_WORD
    COPY -> KeyCode.CLIPBOARD_COPY
    CUT -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD_PASTE
    ONE_HANDED -> KeyCode.TOGGLE_ONE_HANDED_MODE
    INCOGNITO -> KeyCode.TOGGLE_INCOGNITO_MODE
    AUTOCORRECT -> KeyCode.TOGGLE_AUTOCORRECT
    CLEAR_CLIPBOARD -> KeyCode.CLIPBOARD_CLEAR_HISTORY
    CLOSE_HISTORY -> KeyCode.ALPHA
    EMOJI -> KeyCode.EMOJI
    LEFT -> KeyCode.ARROW_LEFT
    RIGHT -> KeyCode.ARROW_RIGHT
    UP -> KeyCode.ARROW_UP
    DOWN -> KeyCode.ARROW_DOWN
    WORD_LEFT -> KeyCode.WORD_LEFT
    WORD_RIGHT -> KeyCode.WORD_RIGHT
    PAGE_UP -> KeyCode.PAGE_UP
    PAGE_DOWN -> KeyCode.PAGE_DOWN
    FULL_LEFT -> KeyCode.MOVE_START_OF_LINE
    FULL_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_START -> KeyCode.MOVE_START_OF_PAGE
    PAGE_END -> KeyCode.MOVE_END_OF_PAGE
    SPLIT -> KeyCode.SPLIT_LAYOUT
    AI_ASSIST -> KeyCode.AI_ASSIST
    AI_CLIPBOARD -> KeyCode.AI_CLIPBOARD
    AI_SLOT_1 -> KeyCode.AI_SLOT_1
    AI_SLOT_2 -> KeyCode.AI_SLOT_2
    AI_SLOT_3 -> KeyCode.AI_SLOT_3
    AI_SLOT_4 -> KeyCode.AI_SLOT_4
    AI_VOICE -> KeyCode.AI_VOICE
    AI_CONVERSATION -> KeyCode.AI_CONVERSATION
    AI_ACTIONS -> KeyCode.AI_ACTIONS
    AI_TONE -> KeyCode.AI_TONE
}

fun getCodeForToolbarKeyLongClick(key: ToolbarKey) = Settings.getInstance().getCustomToolbarLongpressCode(key) ?: when (key) {
    CLIPBOARD -> KeyCode.CLIPBOARD_PASTE
    UNDO -> KeyCode.REDO
    REDO -> KeyCode.UNDO
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_WORD
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_ALL
    COPY -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD
    LEFT -> KeyCode.WORD_LEFT
    RIGHT -> KeyCode.WORD_RIGHT
    UP -> KeyCode.PAGE_UP
    DOWN -> KeyCode.PAGE_DOWN
    WORD_LEFT -> KeyCode.MOVE_START_OF_LINE
    WORD_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_UP -> KeyCode.MOVE_START_OF_PAGE
    PAGE_DOWN -> KeyCode.MOVE_END_OF_PAGE
    else -> KeyCode.UNSPECIFIED
}

// names need to be aligned with resources strings (using lowercase of key.name)
enum class ToolbarKey {
    VOICE, CLIPBOARD, NUMPAD, UNDO, REDO, SETTINGS, SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE, ONE_HANDED, SPLIT,
    INCOGNITO, AUTOCORRECT, CLEAR_CLIPBOARD, CLOSE_HISTORY, EMOJI, LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT,
    PAGE_UP, PAGE_DOWN, FULL_LEFT, FULL_RIGHT, PAGE_START, PAGE_END,
    AI_ASSIST,
    AI_CLIPBOARD,
    AI_SLOT_1,
    AI_SLOT_2,
    AI_SLOT_3,
    AI_SLOT_4,
    AI_VOICE,
    AI_CONVERSATION,
    AI_ACTIONS,
    AI_TONE
}

enum class ToolbarMode {
    EXPANDABLE, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN,
}

val toolbarKeyStrings = entries.associateWithTo(EnumMap(ToolbarKey::class.java)) { it.toString().lowercase(Locale.US) }

val defaultToolbarPref by lazy {
    val default = listOf(AI_ASSIST, AI_TONE, AI_VOICE, SETTINGS, VOICE, CLIPBOARD, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT)
    val others = entries.filterNot { it in default || it == CLOSE_HISTORY }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

private val defaultPinnedKeys = setOf(AI_ASSIST, AI_TONE, AI_CLIPBOARD, AI_SLOT_1, AI_SLOT_2, AI_VOICE, AI_CONVERSATION)
val defaultPinnedToolbarPref = entries.filterNot { it == CLOSE_HISTORY }.joinToString(Separators.ENTRY) {
    it.name + Separators.KV + (it in defaultPinnedKeys)
}

val defaultClipboardToolbarPref by lazy {
    val default = listOf(CLEAR_CLIPBOARD, UP, DOWN, LEFT, RIGHT, UNDO, CUT, COPY, PASTE, SELECT_WORD, CLOSE_HISTORY)
    val others = entries.filterNot { it in default }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

/** add missing keys, typically because a new key has been added */
fun upgradeToolbarPrefs(prefs: SharedPreferences) {
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)
    // Ensure AI keys are enabled and pinned for existing installs
    for (key in defaultPinnedKeys) {
        ensureKeyEnabled(prefs, key)
        ensureKeyPinned(prefs, key)
    }
}

private fun ensureKeyEnabled(prefs: SharedPreferences, key: ToolbarKey) {
    val string = prefs.getString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref) ?: return
    if (string.contains(key.name + Separators.KV + "true")) return
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV)) key.name + Separators.KV + "true" else it
    }
    prefs.edit { putString(Settings.PREF_TOOLBAR_KEYS, result) }
}

private fun ensureKeyPinned(prefs: SharedPreferences, key: ToolbarKey) {
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref) ?: return
    if (string.contains(key.name + Separators.KV + "true")) return
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV)) key.name + Separators.KV + "true" else it
    }
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, result) }
}

private fun upgradeToolbarPref(prefs: SharedPreferences, pref: String, default: String) {
    if (!prefs.contains(pref)) return
    val list = prefs.getString(pref, default)!!.split(Separators.ENTRY).toMutableList()
    val splitDefault = defaultToolbarPref.split(Separators.ENTRY)
    splitDefault.forEach { entry ->
        val keyWithSeparator = entry.substringBefore(Separators.KV) + Separators.KV
        if (list.none { it.startsWith(keyWithSeparator) })
            list.add("${keyWithSeparator}false")
    }
    // likely not needed, but better prepare for possibility of key removal
    list.removeAll {
        try {
            ToolbarKey.valueOf(it.substringBefore(Separators.KV))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
    }
    prefs.edit { putString(pref, list.joinToString(Separators.ENTRY)) }
}

fun getEnabledToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)

fun getPinnedToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)

fun getEnabledClipboardToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)

fun addPinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // remove the existing version of this key and add the enabled one after the last currently enabled key
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val keys = string.split(Separators.ENTRY).toMutableList()
    keys.removeAll { it.startsWith(key.name + Separators.KV) }
    val lastEnabledIndex = keys.indexOfLast { it.endsWith("true") }
    keys.add(lastEnabledIndex + 1, key.name + Separators.KV + "true")
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, keys.joinToString(Separators.ENTRY)) }
}

fun removePinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // just set it to disabled
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV))
            key.name + Separators.KV + "false"
        else it
    }
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, result) }
}

private fun getEnabledToolbarKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val string = prefs.getString(pref, default)!!
    return string.split(Separators.ENTRY).mapNotNull {
        val split = it.split(Separators.KV)
        if (split.last() == "true") {
            try {
                ToolbarKey.valueOf(split.first())
            } catch (_: IllegalArgumentException) {
                null
            }
        } else null
    }
}

fun writeCustomKeyCodes(prefs: SharedPreferences, codes: EnumMap<ToolbarKey, Pair<Int?, Int?>>) {
    val string = codes.mapNotNull { entry -> entry.value?.let { "${entry.key.name},${it.first},${it.second}" } }.joinToString(";")
    prefs.edit { putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, string) }
}

fun readCustomKeyCodes(prefs: SharedPreferences): EnumMap<ToolbarKey, Pair<Int?, Int?>> {
    val map = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
    prefs.getString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Defaults.PREF_TOOLBAR_CUSTOM_KEY_CODES)!!
        .split(";").forEach {
            runCatching {
                val s = it.split(",")
                map[ToolbarKey.valueOf(s[0])] = s[1].toIntOrNull() to s[2].toIntOrNull()
            }
        }
    return map
}

fun getCustomKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.first
}

fun getCustomLongpressKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.second
}

fun clearCustomToolbarKeyCodes() {
    customToolbarKeyCodes = null
}

private var customToolbarKeyCodes: EnumMap<ToolbarKey, Pair<Int?, Int?>>? = null
