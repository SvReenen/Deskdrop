package helium314.keyboard.latin.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.POPUP_KEYS_LABEL_DEFAULT
import helium314.keyboard.latin.utils.POPUP_KEYS_ORDER_DEFAULT
import helium314.keyboard.latin.utils.defaultClipboardToolbarPref
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref

object Defaults {
    fun initDynamicDefaults(context: Context) {
        PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = getTransitionAnimationScale(context) != 0.0f
        val dm = context.resources.displayMetrics
        val px600 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 600f, dm)
        PREF_POPUP_ON = dm.widthPixels >= px600 || dm.heightPixels >= px600
    }

    // must correspond to a file name
    val LayoutType.default get() = when (this) {
        LayoutType.MAIN -> "qwerty"
        LayoutType.SYMBOLS -> "symbols"
        LayoutType.MORE_SYMBOLS -> "symbols_shifted"
        LayoutType.FUNCTIONAL -> if (Settings.getInstance().isTablet) "functional_keys_tablet" else "functional_keys"
        LayoutType.NUMBER -> "number"
        LayoutType.NUMBER_ROW -> "number_row"
        LayoutType.NUMPAD -> "numpad"
        LayoutType.NUMPAD_LANDSCAPE -> "numpad_landscape"
        LayoutType.PHONE -> "phone"
        LayoutType.PHONE_SYMBOLS -> "phone_symbols"
        LayoutType.EMOJI_BOTTOM -> "emoji_bottom_row"
        LayoutType.CLIPBOARD_BOTTOM -> "clip_bottom_row"
    }

    private const val DEFAULT_SIZE_SCALE = 1.0f // 100%
    const val PREF_THEME_STYLE = KeyboardTheme.STYLE_MATERIAL
    const val PREF_ICON_STYLE = KeyboardTheme.STYLE_MATERIAL
    const val PREF_THEME_COLORS = KeyboardTheme.THEME_LIGHT
    const val PREF_THEME_COLORS_NIGHT = KeyboardTheme.THEME_DARK
    const val PREF_THEME_KEY_BORDERS = false
    @JvmField
    val PREF_THEME_DAY_NIGHT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    const val PREF_CUSTOM_ICON_NAMES = ""
    const val PREF_TOOLBAR_CUSTOM_KEY_CODES = ""
    const val PREF_AUTO_CAP = true
    const val PREF_VIBRATE_ON = false
    const val PREF_VIBRATE_IN_DND_MODE = false
    const val PREF_SOUND_ON = false
    const val PREF_SUGGEST_EMOJIS = true
    const val PREF_INLINE_EMOJI_SEARCH = true
    const val PREF_SHOW_EMOJI_DESCRIPTIONS = true
    @JvmField
    var PREF_POPUP_ON = true
    const val PREF_AUTO_CORRECTION = true
    const val PREF_MORE_AUTO_CORRECTION = false
    const val PREF_AUTO_CORRECT_THRESHOLD = 0.185f
    const val PREF_AUTOCORRECT_SHORTCUTS = true
    const val PREF_BACKSPACE_REVERTS_AUTOCORRECT = true
    const val PREF_CENTER_SUGGESTION_TEXT_TO_ENTER = false
    const val PREF_SHOW_SUGGESTIONS = true
    const val PREF_ALWAYS_SHOW_SUGGESTIONS = false
    const val PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT = true
    const val PREF_KEY_USE_PERSONALIZED_DICTS = true
    const val PREF_KEY_USE_DOUBLE_SPACE_PERIOD = true
    const val PREF_BLOCK_POTENTIALLY_OFFENSIVE = true
    const val PREF_SHOW_LANGUAGE_SWITCH_KEY = false
    const val PREF_LANGUAGE_SWITCH_KEY = "internal"
    const val PREF_SHOW_EMOJI_KEY = false
    const val PREF_VARIABLE_TOOLBAR_DIRECTION = true
    const val PREF_ADDITIONAL_SUBTYPES = "de${Separators.SET}${ExtraValue.KEYBOARD_LAYOUT_SET}=MAIN:qwerty${Separators.SETS}" +
            "fr${Separators.SET}${ExtraValue.KEYBOARD_LAYOUT_SET}=MAIN:qwertz${Separators.SETS}" +
            "hu${Separators.SET}${ExtraValue.KEYBOARD_LAYOUT_SET}=MAIN:qwerty"
    const val PREF_ENABLE_SPLIT_KEYBOARD = false
    const val PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE = false
    @JvmField
    val PREF_SPLIT_SPACER_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_KEYBOARD_HEIGHT_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_BOTTOM_ROW_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_BOTTOM_PADDING_SCALE = arrayOf(DEFAULT_SIZE_SCALE, 0f)
    @JvmField
    val PREF_SIDE_PADDING_SCALE = Array(4) { 0f }
    const val PREF_FONT_SCALE = DEFAULT_SIZE_SCALE
    const val PREF_EMOJI_FONT_SCALE = DEFAULT_SIZE_SCALE
    const val PREF_EMOJI_KEY_FIT = true
    const val PREF_EMOJI_SKIN_TONE = ""
    const val PREF_SPACE_HORIZONTAL_SWIPE = "move_cursor"
    const val PREF_SPACE_VERTICAL_SWIPE = "none"
    const val PREF_DELETE_SWIPE = true
    const val PREF_AUTOSPACE_AFTER_PUNCTUATION = false
    const val PREF_AUTOSPACE_AFTER_SUGGESTION = true
    const val PREF_AUTOSPACE_AFTER_GESTURE_TYPING = true
    const val PREF_AUTOSPACE_BEFORE_GESTURE_TYPING = true
    const val PREF_SHIFT_REMOVES_AUTOSPACE = false
    const val PREF_ALWAYS_INCOGNITO_MODE = false
    const val PREF_BIGRAM_PREDICTIONS = true
    const val PREF_SUGGEST_PUNCTUATION = false
    const val PREF_SUGGEST_CLIPBOARD_CONTENT = true
    const val PREF_GESTURE_INPUT = true
    const val PREF_VIBRATION_DURATION_SETTINGS = -1
    const val PREF_KEYPRESS_SOUND_VOLUME = -0.01f
    const val PREF_KEY_LONGPRESS_TIMEOUT = 300
    const val PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY = true
    const val PREF_GESTURE_PREVIEW_TRAIL = true
    const val PREF_GESTURE_FLOATING_PREVIEW_TEXT = true
    const val PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC = true
    @JvmField
    var PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = true
    const val PREF_GESTURE_SPACE_AWARE = false
    const val PREF_GESTURE_FAST_TYPING_COOLDOWN = 500
    const val PREF_GESTURE_TRAIL_FADEOUT_DURATION = 800
    const val PREF_SHOW_SETUP_WIZARD_ICON = true
    const val PREF_USE_CONTACTS = false
    const val PREF_USE_APPS = false
    const val PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD = false
    const val PREF_ONE_HANDED_MODE = false
    @SuppressLint("RtlHardcoded")
    const val PREF_ONE_HANDED_GRAVITY = Gravity.LEFT
    const val PREF_ONE_HANDED_SCALE = 1f
    const val PREF_SHOW_NUMBER_ROW = false
    const val PREF_SHOW_NUMBER_ROW_IN_SYMBOLS = true
    const val PREF_LOCALIZED_NUMBER_ROW = true
    const val PREF_SHOW_NUMBER_ROW_HINTS = false
    const val PREF_CUSTOM_CURRENCY_KEY = ""
    const val PREF_SHOW_HINTS = true
    const val PREF_POPUP_KEYS_ORDER = POPUP_KEYS_ORDER_DEFAULT
    const val PREF_POPUP_KEYS_LABELS_ORDER = POPUP_KEYS_LABEL_DEFAULT
    const val PREF_SHOW_POPUP_HINTS = false
    const val PREF_SHOW_TLD_POPUP_KEYS = true
    const val PREF_MORE_POPUP_KEYS = "main"
    const val PREF_SPACE_TO_CHANGE_LANG = true
    const val PREF_LANGUAGE_SWIPE_DISTANCE = 5
    const val PREF_ENABLE_CLIPBOARD_HISTORY = true
    const val PREF_CLIPBOARD_HISTORY_RETENTION_TIME = 10 // minutes
    const val PREF_CLIPBOARD_HISTORY_PINNED_FIRST = true
    const val PREF_ADD_TO_PERSONAL_DICTIONARY = false
    @JvmField
    val PREF_NAVBAR_COLOR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    const val PREF_NARROW_KEY_GAPS = false
    const val PREF_ENABLED_SUBTYPES = ""
    const val PREF_SELECTED_SUBTYPE = ""
    const val PREF_URL_DETECTION = false
    const val PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG = false
    const val PREF_TOOLBAR_MODE = "EXPANDABLE"
    const val PREF_TOOLBAR_HIDING_GLOBAL = true
    const val PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE = false
    const val PREF_QUICK_PIN_TOOLBAR_KEYS = false
    val PREF_PINNED_TOOLBAR_KEYS = defaultPinnedToolbarPref
    val PREF_TOOLBAR_KEYS = defaultToolbarPref
    const val PREF_AUTO_SHOW_TOOLBAR = false
    const val PREF_AUTO_HIDE_TOOLBAR = false
    val PREF_CLIPBOARD_TOOLBAR_KEYS = defaultClipboardToolbarPref
    const val PREF_ABC_AFTER_EMOJI = false
    const val PREF_ABC_AFTER_CLIP = false
    const val PREF_ABC_AFTER_SYMBOL_SPACE = true
    const val PREF_ABC_AFTER_NUMPAD_SPACE = false
    const val PREF_REMOVE_REDUNDANT_POPUPS = false
    const val PREF_SPACE_BAR_TEXT = ""
    const val PREF_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
    const val PREF_EMOJI_RECENT_KEYS = ""
    const val PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID = 0
    const val PREF_SHOW_DEBUG_SETTINGS = false
    val PREF_DEBUG_MODE = BuildConfig.DEBUG
    const val PREF_SHOW_SUGGESTION_INFOS = false
    const val PREF_FORCE_NON_DISTINCT_MULTITOUCH = false
    const val PREF_SLIDING_KEY_INPUT_PREVIEW = true
    const val PREF_USER_COLORS = "[]"
    const val PREF_USER_MORE_COLORS = 0
    const val PREF_USER_ALL_COLORS = ""
    const val PREF_SAVE_SUBTYPE_PER_APP = false

    // AI settings
    const val PREF_AI_BACKEND = "groq"
    const val PREF_AI_MODEL = "groq:meta-llama/llama-4-scout-17b-16e-instruct"
    const val PREF_AI_MODEL_FILTER = "both"
    const val PREF_GEMINI_API_KEY = ""
    const val PREF_GROQ_API_KEY = ""
    const val PREF_OPENROUTER_API_KEY = ""
    const val PREF_OPENROUTER_CUSTOM_MODELS = ""
    const val PREF_ANTHROPIC_API_KEY = ""
    const val PREF_OPENAI_API_KEY = ""
    const val PREF_BRAVE_SEARCH_API_KEY = ""
    const val PREF_TAVILY_API_KEY = ""
    const val PREF_OLLAMA_URL = "http://localhost:11434"
    const val PREF_OLLAMA_URL_FALLBACK = ""
    const val PREF_OLLAMA_MODEL = "gemma3:4b"
    const val PREF_OPENAI_COMPAT_URL = ""
    const val PREF_OPENAI_COMPAT_URL_FALLBACK = ""
    const val PREF_OPENAI_COMPAT_API_KEY = ""
    const val PREF_AI_INSTRUCTION = "Improve this text. Fix grammar and spelling. Keep the same language. Return only the improved text, nothing else."
    const val PREF_AI_LOREBOOK = ""
    const val PREF_AI_ALLOW_NETWORK_TOOLS = false
    const val PREF_AI_ALLOW_ACTIONS = false
    const val PREF_AI_INLINE_MODEL = "" // empty = use main AI model
    const val PREF_AI_CONVERSATION_MODEL = "" // empty = use main AI model
    const val PREF_AI_SLOT_1_MODEL = ""
    const val PREF_AI_SLOT_2_MODEL = ""
    const val PREF_AI_SLOT_3_MODEL = ""
    const val PREF_AI_SLOT_4_MODEL = ""
    const val PREF_AI_CLOUD_PRESETS = "[]"
    const val PREF_AI_VOICE_MODE = 0
    const val PREF_AI_VOICE_MODEL = "" // empty = use main AI model
    const val PREF_AI_VOICE_CUSTOM_MODES = "[]" // JSON array of {name, prompt}
    const val PREF_AI_VOICE_ENGINE = "google"
    const val PREF_AI_VOICE_HINT_SHOWN_COUNT = 0
    const val PREF_AI_MCP_MODEL = "" // empty = use main AI model
    const val PREF_AI_PROMPT_ALIASES = """[{"name":"formal","prompt":"Rewrite this text in a more formal and professional tone. Return only the rewritten text, nothing else."},{"name":"grammar","prompt":"Fix all grammar, spelling, and punctuation errors in this text. Do not change the meaning or tone. Return only the corrected text, nothing else."},{"name":"summarize","prompt":"Summarize this text in 1-3 concise sentences. Return only the summary, nothing else."},{"name":"shorten","prompt":"Make this text shorter and more concise while keeping the same meaning. Return only the shortened text, nothing else."},{"name":"reply","prompt":"Write a short, natural reply to this message. Match the tone and language of the original. Return only the reply, nothing else."}]"""
    const val PREF_AI_TONE_MODEL = "" // empty = use main AI model
    const val PREF_AI_ASSIST_MODEL = "" // empty = use main AI model
    const val PREF_AI_TONE_CHIPS = """[{"name":"Formal","prompt":"Rewrite this text in a more formal and professional tone. Keep the same language as the original text. Return only the rewritten text, nothing else."},{"name":"Casual","prompt":"Rewrite this text in a casual, relaxed tone. Keep the same language as the original text. Return only the rewritten text, nothing else."},{"name":"Friendly","prompt":"Rewrite this text in a warm, friendly tone. Keep the same language as the original text. Return only the rewritten text, nothing else."},{"name":"Shorter","prompt":"Make this text shorter and more concise. Keep the same meaning, tone, and language. Return only the rewritten text, nothing else."},{"name":"Longer","prompt":"Expand this text with more detail while keeping the same tone, meaning, and language. Return only the rewritten text, nothing else."},{"name":"Grammar","prompt":"Fix all grammar, spelling, and punctuation errors in this text. Do not change the meaning, tone, or language. Return only the corrected text, nothing else."},{"name":"English","prompt":"Translate this text to English. Return only the translation, nothing else."}]"""
    const val PREF_AI_CLOUD_FALLBACK = false

    const val PREF_WHISPER_URL = ""
    const val PREF_WHISPER_URL_FALLBACK = ""
    const val PREF_WHISPER_MODEL = "Systran/faster-whisper-base"
    const val PREF_SYNC_SERVER_URL = ""
    const val PREF_SYNC_TOKEN = ""
    const val PREF_SYNC_ENABLED = false
    const val PREF_DESKDROP_ONBOARDING_DONE = false

    // Default voice mode prompts (used as fallback when no custom prompt is set)
    val AI_VOICE_MODE_NAMES = arrayOf("Smart (auto-detect)", "Translate to English", "Translate to Dutch", "Formal", "Bullet points", "Chat message")
    val AI_VOICE_MODE_PROMPTS = arrayOf(
        "This is voice input from a user. Determine the intent:\n- If it sounds like dictated text (contains filler words, lacks structure), clean it up: fix punctuation, remove filler words, make it well-structured.\n- If it sounds like an instruction or request (e.g. 'write an email about...', 'summarize...', 'translate...'), follow the instruction and generate the requested content.\nKeep the same language as the input. Return only the final text, no explanations.",
        "Translate this voice input to English. Return only the translated text.",
        "Translate this voice input to Dutch. Return only the translated text.",
        "Rewrite this voice input in a formal, professional tone. Fix punctuation and remove filler words. Keep the same language. Return only the formal text.",
        "Convert this voice input into a clean bullet point list. Keep the same language. Return only the list.",
        "Clean up this voice input into a natural chat message. Keep it short and conversational, like a WhatsApp text. Remove filler words (um, uh, like), fix punctuation, but do NOT make it formal or professional. Use the same language as the input. Add emoji only if the tone clearly calls for it. Return only the message, nothing else."
    )

    // Special reply-to-clipboard prompt (not a regular voice mode, triggered separately)
    const val AI_VOICE_REPLY_PROMPT = "The user copied a message and then dictated a reply instruction via voice.\n\nCopied message (context):\n{clipboard}\n\nUser's voice instruction:\n{voice_input}\n\nWrite a natural reply to the copied message based on the user's instruction. Match the tone and language of the original message. Return only the reply, nothing else."
}
