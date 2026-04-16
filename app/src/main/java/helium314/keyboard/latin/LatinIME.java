/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Message;
import android.os.Process;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.view.inputmethod.InputMethodSubtype;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.compat.ConfigurationCompatKt;
import helium314.keyboard.compat.EditorInfoCompatUtils;
import helium314.keyboard.compat.ImeCompat;
import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardActionListenerImpl;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity;
import helium314.keyboard.keyboard.internal.KeyboardIconsSet;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.InsetsOutlineProvider;
import helium314.keyboard.dictionarypack.DictionaryPackConstants;
import helium314.keyboard.event.Event;
import helium314.keyboard.event.InputTransaction;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardId;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.common.InputPointers;
import helium314.keyboard.latin.common.ViewOutlineProviderUtilsKt;
import helium314.keyboard.latin.define.DebugFlags;
import helium314.keyboard.latin.inputlogic.InputLogic;
import helium314.keyboard.latin.personalization.PersonalizationHelper;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.suggestions.SuggestionStripViewAccessor;
import helium314.keyboard.latin.touchinputconsumer.GestureConsumer;
import helium314.keyboard.latin.utils.ColorUtilKt;
import helium314.keyboard.latin.utils.GestureDataGatheringKt;
import helium314.keyboard.latin.utils.InlineAutofillUtils;
import helium314.keyboard.latin.utils.InputMethodPickerKt;
import helium314.keyboard.latin.utils.JniUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LeakGuardHandlerWrapper;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.StatsUtils;
import helium314.keyboard.latin.utils.StatsUtilsManager;
import helium314.keyboard.latin.utils.SubtypeLocaleUtils;
import helium314.keyboard.latin.utils.SubtypeSettings;
import helium314.keyboard.latin.utils.SubtypeState;
import helium314.keyboard.latin.utils.ToolbarMode;
import helium314.keyboard.settings.SettingsActivity2;
import kotlin.Unit;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements
        SuggestionStripView.Listener, SuggestionStripViewAccessor,
        DictionaryFacilitator.DictionaryInitializationListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS = TimeUnit.SECONDS.toMillis(2);
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    /**
     * The name of the scheme used by the Package Manager to warn of a new package installation,
     * replacement or removal.
     */
    private static final String SCHEME_PACKAGE = "package";

    final Settings mSettings;
    public final KeyboardActionListener mKeyboardActionListener;
    private int mOriginalNavBarColor = 0;
    private int mOriginalNavBarFlags = 0;

    // UIHandler is needed when creating InputLogic
    public final UIHandler mHandler = new UIHandler(this);
    private DictionaryFacilitator mDictionaryFacilitator = // non-final for active gesture data gathering, revert when data gathering phase is done (end of 2026 latest)
            DictionaryFacilitatorProvider.getDictionaryFacilitator(false);
    private final DictionaryFacilitator mOriginalDictionaryFacilitator = mDictionaryFacilitator;
    final InputLogic mInputLogic = new InputLogic(this, this, mDictionaryFacilitator);

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    private View mInputView;
    private InsetsOutlineProvider mInsetsUpdater;
    private SuggestionStripView mSuggestionStripView;

    // Pending text to insert from ResultViewActivity
    private static volatile String sPendingInsert = null;
    private static volatile boolean sPendingReopenAiDialog = false;

    public static void setPendingInsert(String text) {
        sPendingInsert = text;
    }

    public static void setPendingReopenAiDialog() {
        sPendingReopenAiDialog = true;
    }

    // Active dialog EditText for routing keyboard input to IME's own dialogs
    private android.widget.EditText mDialogEditText = null;
    private int mDialogCursorPos = 0;
    private android.app.AlertDialog mActiveDialog = null;

    public void setActiveDialog(android.app.AlertDialog dialog) {
        mActiveDialog = dialog;
    }

    public android.app.AlertDialog getActiveDialog() {
        return mActiveDialog;
    }

    public void setDialogEditText(android.widget.EditText editText) {
        mDialogEditText = editText;
        if (editText != null) {
            mDialogCursorPos = editText.getText().length();
            android.text.Selection.setSelection(editText.getText(), mDialogCursorPos);
        }
        // Force shift state refresh: unshift when dialog opens, re-evaluate when dialog closes
        mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    public android.widget.EditText getDialogEditText() {
        return mDialogEditText;
    }

    public int getDialogCursorPos() {
        return mDialogCursorPos;
    }

    public void setDialogCursorPos(int pos) {
        mDialogCursorPos = pos;
        if (mDialogEditText != null) {
            int clamped = Math.max(0, Math.min(pos, mDialogEditText.getText().length()));
            mDialogCursorPos = clamped;
            android.text.Selection.setSelection(mDialogEditText.getText(), clamped);
        }
    }

    public KeyboardSwitcher getKeyboardSwitcher() { return mKeyboardSwitcher; }
    public InputLogic getInputLogic() { return mInputLogic; }

    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;
    private final SubtypeState mSubtypeState = new SubtypeState((InputMethodSubtype subtype) -> { switchToSubtype(subtype); return Unit.INSTANCE; });
    private final StatsUtilsManager mStatsUtilsManager;
    // Working variable for {@link #startShowingInputView()} and
    // {@link #onEvaluateInputViewShown()}.
    private boolean mIsExecutingStartShowingInputView;

    // Used for re-initialize keyboard layout after onConfigurationChange.
    @Nullable
    private Context mDisplayContext;

    // Object for reacting to adding/removing a dictionary pack.
    private final BroadcastReceiver mDictionaryPackInstallReceiver =
            new DictionaryPackInstallBroadcastReceiver(this);

    private final BroadcastReceiver mDictionaryDumpBroadcastReceiver =
            new DictionaryDumpBroadcastReceiver(this);

    final static class RestartAfterDeviceUnlockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            // Restart the keyboard if credential encrypted storage is unlocked. This reloads the
            // dictionary and other data from credential-encrypted storage (with the onCreate()
            // method).
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                final int myPid = Process.myPid();
                Log.i(TAG, "Killing my process: pid=" + myPid);
                Process.killProcess(myPid);
            } else {
                Log.e(TAG, "Unexpected intent " + intent);
            }
        }
    }
    final RestartAfterDeviceUnlockReceiver mRestartAfterDeviceUnlockReceiver = new RestartAfterDeviceUnlockReceiver();

    private AlertDialog mOptionsDialog;

    private final boolean mIsHardwareAcceleratedDrawingEnabled;

    private GestureConsumer mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;

    private final ClipboardHistoryManager mClipboardHistoryManager = new ClipboardHistoryManager(this);

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 0;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_UPDATE_SUGGESTION_STRIP = 2;
        private static final int MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS = 3;
        private static final int MSG_RESUME_SUGGESTIONS = 4;
        private static final int MSG_REOPEN_DICTIONARIES = 5;
        private static final int MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED = 6;
        private static final int MSG_RESET_CACHES = 7;
        private static final int MSG_WAIT_FOR_DICTIONARY_LOAD = 8;
        private static final int MSG_DEALLOCATE_MEMORY = 9;
        private static final int MSG_SWITCH_LANGUAGE_AUTOMATICALLY = 10;
        // Update this when adding new messages
        private static final int MSG_LAST = MSG_SWITCH_LANGUAGE_AUTOMATICALLY;

        private static final int ARG1_NOT_GESTURE_INPUT = 0;
        private static final int ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT = 1;
        private static final int ARG1_SHOW_GESTURE_FLOATING_PREVIEW_TEXT = 2;
        private static final int ARG2_UNUSED = 0;
        private static final int ARG1_TRUE = 1;

        private int mDelayInMillisecondsToUpdateSuggestions;
        private int mDelayInMillisecondsToUpdateShiftState;

        public UIHandler(@NonNull final LatinIME ownerInstance) {
            super(ownerInstance);
        }

        public void onCreate() {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            final Resources res = latinIme.getResources();
            mDelayInMillisecondsToUpdateSuggestions = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_suggestions);
            mDelayInMillisecondsToUpdateShiftState = res.getInteger(
                    R.integer.config_delay_in_milliseconds_to_update_shift_state);
        }

        @Override
        public void handleMessage(@NonNull final Message msg) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            switch (msg.what) {
                case MSG_UPDATE_SUGGESTION_STRIP:
                    cancelUpdateSuggestionStrip();
                    latinIme.mInputLogic.performUpdateSuggestionStripSync(
                            latinIme.mSettings.getCurrent(), msg.arg1 /* inputStyle */);
                    break;
                case MSG_UPDATE_SHIFT_STATE:
                    latinIme.mKeyboardSwitcher.requestUpdatingShiftState(latinIme.getCurrentAutoCapsState(),
                            latinIme.getCurrentRecapitalizeState());
                    break;
                case MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS:
                    if (msg.arg1 == ARG1_NOT_GESTURE_INPUT) {
                        final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                        latinIme.setSuggestedWords(suggestedWords);
                    } else {
                        latinIme.showGesturePreviewAndSetSuggestions((SuggestedWords) msg.obj,
                                msg.arg1 == ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT);
                    }
                    break;
                case MSG_RESUME_SUGGESTIONS:
                    latinIme.mInputLogic.restartSuggestionsOnWordTouchedByCursor(
                            latinIme.mSettings.getCurrent(),
                            latinIme.mKeyboardSwitcher.getCurrentKeyboardScript());
                    break;
                case MSG_REOPEN_DICTIONARIES:
                    // We need to re-evaluate the currently composing word in case the script has
                    // changed.
                    postWaitForDictionaryLoad();
                    latinIme.resetDictionaryFacilitatorIfNecessary();
                    break;
                case MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED:
                    final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                    latinIme.mInputLogic.onUpdateTailBatchInputCompleted(
                            latinIme.mSettings.getCurrent(),
                            suggestedWords, latinIme.mKeyboardSwitcher);
                    latinIme.onTailBatchInputResultShown(suggestedWords);
                    break;
                case MSG_RESET_CACHES:
                    final SettingsValues settingsValues = latinIme.mSettings.getCurrent();
                    if (latinIme.mInputLogic.retryResetCachesAndReturnSuccess(
                            msg.arg1 == ARG1_TRUE /* tryResumeSuggestions */,
                            msg.arg2 /* remainingTries */, this /* handler */)) {
                        // If we were able to reset the caches, then we can reload the keyboard.
                        // Otherwise, we'll do it when we can.
                        latinIme.mKeyboardSwitcher.reloadMainKeyboard();
                    }
                    break;
                case MSG_WAIT_FOR_DICTIONARY_LOAD:
                    Log.i(TAG, "Timeout waiting for dictionary load");
                    break;
                case MSG_DEALLOCATE_MEMORY:
                    latinIme.deallocateMemory();
                    break;
                case MSG_SWITCH_LANGUAGE_AUTOMATICALLY:
                    latinIme.switchToSubtype((InputMethodSubtype) msg.obj);
                    break;
            }
        }

        public void postUpdateSuggestionStrip(final int inputStyle) {
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SUGGESTION_STRIP, inputStyle,
                    0 /* ignored */), mDelayInMillisecondsToUpdateSuggestions);
        }

        public void postReopenDictionaries() {
            sendMessage(obtainMessage(MSG_REOPEN_DICTIONARIES));
        }

        public void postResumeSuggestions(final boolean shouldDelay) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            if (!latinIme.mSettings.getCurrent().needsToLookupSuggestions()) {
                return;
            }
            removeMessages(MSG_RESUME_SUGGESTIONS);
            final int message = MSG_RESUME_SUGGESTIONS;
            if (shouldDelay) {
                sendMessageDelayed(obtainMessage(message),
                        mDelayInMillisecondsToUpdateSuggestions);
            } else {
                sendMessage(obtainMessage(message));
            }
        }

        public void postResetCaches(final boolean tryResumeSuggestions, final int remainingTries) {
            removeMessages(MSG_RESET_CACHES);
            sendMessage(obtainMessage(MSG_RESET_CACHES, tryResumeSuggestions ? 1 : 0,
                    remainingTries, null));
        }

        public void postWaitForDictionaryLoad() {
            sendMessageDelayed(obtainMessage(MSG_WAIT_FOR_DICTIONARY_LOAD),
                    DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS);
        }

        public void cancelWaitForDictionaryLoad() {
            removeMessages(MSG_WAIT_FOR_DICTIONARY_LOAD);
        }

        public boolean hasPendingWaitForDictionaryLoad() {
            return hasMessages(MSG_WAIT_FOR_DICTIONARY_LOAD);
        }

        public void cancelUpdateSuggestionStrip() {
            removeMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public void cancelResumeSuggestions() {
            removeMessages(MSG_RESUME_SUGGESTIONS);
        }

        public boolean hasPendingUpdateSuggestions() {
            return hasMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public boolean hasPendingResumeSuggestions() {
            return hasMessages(MSG_RESUME_SUGGESTIONS);
        }

        public boolean hasPendingReopenDictionaries() {
            return hasMessages(MSG_REOPEN_DICTIONARIES);
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE),
                    mDelayInMillisecondsToUpdateShiftState);
        }

        public void postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS);
        }

        public void cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY);
        }

        public boolean hasPendingDeallocateMemory() {
            return hasMessages(MSG_DEALLOCATE_MEMORY);
        }

        public void removeAllMessages() {
            for (int i = 0; i <= MSG_LAST; ++i) {
                removeMessages(i);
            }
        }

        public void showGesturePreviewAndSetSuggestions(final SuggestedWords suggestedWords,
                                                        final boolean dismissGestureFloatingPreviewText) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS);
            final int arg1 = dismissGestureFloatingPreviewText
                    ? ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT
                    : ARG1_SHOW_GESTURE_FLOATING_PREVIEW_TEXT;
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS, arg1,
                    ARG2_UNUSED, suggestedWords).sendToTarget();
        }

        public void setSuggestions(final SuggestedWords suggestedWords) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS);
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SET_SUGGESTIONS,
                    ARG1_NOT_GESTURE_INPUT, ARG2_UNUSED, suggestedWords).sendToTarget();
        }

        public void showTailBatchInputResult(final SuggestedWords suggestedWords) {
            obtainMessage(MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED, suggestedWords).sendToTarget();
        }

        public void postSwitchLanguage(final InputMethodSubtype subtype) {
            obtainMessage(MSG_SWITCH_LANGUAGE_AUTOMATICALLY, subtype).sendToTarget();
        }

        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        public void startOrientationChanging() {
            removeMessages(MSG_PENDING_IMS_CALLBACK);
            resetPendingImsCallback();
            mIsOrientationChanging = true;
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            if (latinIme.isInputViewShown()) {
                latinIme.mKeyboardSwitcher.saveKeyboardState();
            }
        }

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                                               boolean restarting) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal();
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputInternal(editorInfo, restarting);
                }
            }
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS);
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputViewInternal(editorInfo, restarting);
                    mAppliedEditorInfo = editorInfo;
                }
                cancelDeallocateMemory();
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput);
                    mAppliedEditorInfo = null;
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory();
                }
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false);
                    latinIme.onFinishInputInternal();
                }
            }
        }
    }

    // Loading the native library eagerly to avoid unexpected UnsatisfiedLinkError at the initial
    // JNI call as much as possible.
    static {
        JniUtils.loadNativeLibrary();
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mStatsUtilsManager = StatsUtilsManager.getInstance();
        mKeyboardActionListener = new KeyboardActionListenerImpl(this, mInputLogic);
        mIsHardwareAcceleratedDrawingEnabled = this.enableHardwareAcceleration();
        Log.i(TAG, "Hardware accelerated drawing: " + mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void onCreate() {
        mSettings.startListener();
        KeyboardIconsSet.Companion.getInstance().loadIcons(this);
        mRichImm = RichInputMethodManager.getInstance();
        AudioAndHapticFeedbackManager.init(this);
        AccessibilityUtils.init(this);
        mStatsUtilsManager.onCreate(this, mDictionaryFacilitator);
        mDisplayContext = KtxKt.getDisplayContext(this);
        KeyboardSwitcher.init(this);
        super.onCreate();

        loadSettings();
        helium314.keyboard.latin.ai.AiServiceSync.setContext(this);
        helium314.keyboard.latin.ai.SecureApiKeys.init(this);
        helium314.keyboard.latin.ai.SecureApiKeys.migrateFromPlainPrefs(
            helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this));
        mClipboardHistoryManager.onCreate();
        mHandler.onCreate();

        // Register to receive ringer mode change.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);

        // Register to receive installation and removal of a dictionary pack.
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme(SCHEME_PACKAGE);
        registerReceiver(mDictionaryPackInstallReceiver, packageFilter);

        final IntentFilter newDictFilter = new IntentFilter();
        newDictFilter.addAction(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION);
        // RECEIVER_EXPORTED is necessary because apparently Android 15 (and others?) don't recognize if the sender and receiver are the same app, see https://github.com/HeliBorg/HeliBoard/pull/1756
        ContextCompat.registerReceiver(this, mDictionaryPackInstallReceiver, newDictFilter, ContextCompat.RECEIVER_EXPORTED);

        final IntentFilter dictDumpFilter = new IntentFilter();
        dictDumpFilter.addAction(DictionaryDumpBroadcastReceiver.DICTIONARY_DUMP_INTENT_ACTION);
        ContextCompat.registerReceiver(this, mDictionaryDumpBroadcastReceiver, dictDumpFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        final IntentFilter restartAfterUnlockFilter = new IntentFilter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            restartAfterUnlockFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver(mRestartAfterDeviceUnlockReceiver, restartAfterUnlockFilter);

        StatsUtils.onCreate(mSettings.getCurrent(), mRichImm);
    }

    private void loadSettings() {
        final Locale locale = mRichImm.getCurrentSubtypeLocale();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(
                editorInfo, isFullscreenMode(), getPackageName());
        mSettings.loadSettings(this, locale, inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
        // This method is called on startup and language switch, before the new layout has
        // been displayed. Opening dictionaries never affects responsivity as dictionaries are
        // asynchronously loaded.
        if (!mHandler.hasPendingReopenDictionaries()) {
            resetDictionaryFacilitatorIfNecessary();
        }
        refreshPersonalizationDictionarySession(currentSettingsValues);
        mInputLogic.mSuggest.clearNextWordSuggestionsCache();
        mInputLogic.updateEmojiDictionary(locale);
        mStatsUtilsManager.onLoadSettings(this, currentSettingsValues);
    }

    private void refreshPersonalizationDictionarySession(
            final SettingsValues currentSettingsValues) {
        if (!currentSettingsValues.mUsePersonalizedDicts) {
            // Remove user history dictionaries.
            PersonalizationHelper.removeAllUserHistoryDictionaries(this);
            mDictionaryFacilitator.clearUserHistoryDictionary(this);
        }
    }

    // Note that this method is called from a non-UI thread.
    @Override
    public void onUpdateMainDictionaryAvailability(final boolean isMainDictionaryAvailable) {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.setMainDictionaryAvailability(isMainDictionaryAvailable);
        }
        if (mHandler.hasPendingWaitForDictionaryLoad()) {
            mHandler.cancelWaitForDictionaryLoad();
            mHandler.postResumeSuggestions(false /* shouldDelay */);
        }
    }

    void resetDictionaryFacilitatorIfNecessary() {
        final Locale subtypeSwitcherLocale = mRichImm.getCurrentSubtypeLocale();
        final Locale subtypeLocale;
        if (subtypeSwitcherLocale == null) {
            // This happens in very rare corner cases - for example, immediately after a switch
            // to LatinIME has been requested, about a frame later another switch happens. In this
            // case, we are about to go down but we still don't know it, however the system tells
            // us there is no current subtype.
            Log.e(TAG, "System is reporting no current subtype.");
            subtypeLocale = ConfigurationCompatKt.locale(getResources().getConfiguration());
        } else {
            subtypeLocale = subtypeSwitcherLocale;
        }
        final ArrayList<Locale> locales = new ArrayList<>();
        locales.add(subtypeLocale);
        locales.addAll(mSettings.getCurrent().mSecondaryLocales);
        if (mDictionaryFacilitator.usesSameSettings(
                locales,
                mSettings.getCurrent().mUseContactsDictionary,
                mSettings.getCurrent().mUseAppsDictionary,
                mSettings.getCurrent().mUsePersonalizedDicts
        )) {
            return;
        }
        resetDictionaryFacilitator(subtypeLocale);
    }

    /**
     * Reset the facilitator by loading dictionaries for the given locale and
     * the current settings values.
     *
     * @param locale the locale
     */
    // TODO: make sure the current settings always have the right locales, and read from them.
    private void resetDictionaryFacilitator(@NonNull final Locale locale) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        try {
            mDictionaryFacilitator.resetDictionaries(this, locale,
                settingsValues.mUseContactsDictionary, settingsValues.mUseAppsDictionary,
                settingsValues.mUsePersonalizedDicts, false, "", this);
        } catch (Throwable e) {
            // this should not happen, but in case it does we at least want to show a keyboard
            Log.e(TAG, "Could not reset dictionary facilitator, please fix ASAP", e);
        }
        mInputLogic.mSuggest.setAutoCorrectionThreshold(settingsValues.mAutoCorrectionThreshold);
    }

    /**
     * Reset suggest by loading the main dictionary of the current locale.
     */
    /* package private */ void resetSuggestMainDict() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        mDictionaryFacilitator.resetDictionaries(this, mDictionaryFacilitator.getMainLocale(),
                settingsValues.mUseContactsDictionary, settingsValues.mUseAppsDictionary,
                settingsValues.mUsePersonalizedDicts, true, "", this);
        mKeyboardSwitcher.setThemeNeedsReload(); // necessary for emoji search
        EmojiPalettesView.closeDictionaryFacilitator();
        EmojiSearchActivity.Companion.closeDictionaryFacilitator();
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mDictionaryFacilitator.localesAndConfidences();
    }

    @Override
    public void onDestroy() {
        mClipboardHistoryManager.onDestroy();
        mDictionaryFacilitator.closeDictionaries();
        mSettings.onDestroy();
        unregisterReceiver(mRingerModeChangeReceiver);
        unregisterReceiver(mDictionaryPackInstallReceiver);
        unregisterReceiver(mDictionaryDumpBroadcastReceiver);
        unregisterReceiver(mRestartAfterDeviceUnlockReceiver);
        mStatsUtilsManager.onDestroy(this /* context */);
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        deallocateMemory();
    }

    private boolean isImeSuppressedByHardwareKeyboard() {
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        SettingsValues settingsValues = mSettings.getCurrent();
        Log.i(TAG, "onConfigurationChanged");
        SubtypeSettings.INSTANCE.reloadSystemLocales(this);
        if (settingsValues.mDisplayOrientation != conf.orientation) {
            mHandler.startOrientationChanging();
            mInputLogic.onOrientationChange(mSettings.getCurrent());
        }
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            // If the state of having a hardware keyboard changed, then we want to reload the
            // settings to adjust for that.
            // TODO: we should probably do this unconditionally here, rather than only when we
            // have a change in hardware keyboard configuration.
            loadSettings();
            if (isImeSuppressedByHardwareKeyboard()) {
                // We call cleanupInternalStateForFinishInput() because it's the right thing to do;
                // however, it seems at the moment the framework is passing us a seemingly valid
                // but actually non-functional InputConnection object. So if this bug ever gets
                // fixed we'll be able to remove the composition, but until it is this code is
                // actually not doing much.
                cleanupInternalStateForFinishInput();
            }
        }
        // KeyboardSwitcher will check by itself if theme update is necessary
        mKeyboardSwitcher.updateKeyboardTheme(KtxKt.getDisplayContext(this));
        super.onConfigurationChanged(conf);
    }

    @Override
    public void onInitializeInterface() {
        mDisplayContext = KtxKt.getDisplayContext(this);
        Log.d(TAG, "onInitializeInterface");
        mKeyboardSwitcher.updateKeyboardTheme(mDisplayContext);
    }

    @Override
    public View onCreateInputView() {
        StatsUtils.onCreateInputView();
        return mKeyboardSwitcher.onCreateInputView(KtxKt.getDisplayContext(this), mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void setInputView(final View view) {
        super.setInputView(view);
        mInputView = view;
        mInsetsUpdater = ViewOutlineProviderUtilsKt.setInsetsOutlineProvider(view);
        KtxKt.updateSoftInputWindowLayoutParameters(this, mInputView);
        updateSuggestionStripView(view);
    }

    public void updateSuggestionStripView(View view) {
        mSuggestionStripView = mSettings.getCurrent().mToolbarMode == ToolbarMode.HIDDEN || isEmojiSearch()?
                        null : view.findViewById(R.id.suggestion_strip_view);
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setRtl(mRichImm.getCurrentSubtype().isRtlSubtype());
            mSuggestionStripView.setListener(this, view);
        }
    }

    @Override
    public void setCandidatesView(final View view) {
        // To ensure that CandidatesView will never be set.
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
        mStatsUtilsManager.onStartInputView();
        // Re-evaluate reminder accent tint on every keyboard show —
        // setListener() only runs on input-view (re)creation, so a reminder
        // firing mid-session wouldn't otherwise refresh the AI_CONVERSATION
        // button tint.
        if (hasSuggestionStripView()) {
            mSuggestionStripView.refreshReminderAccent();
            mSuggestionStripView.refreshCloudFallbackBadges();
        }
        // Check for pending actions from ConversationActivity / ResultViewActivity.
        // These may come from the :chat process via PendingInsertBridge (file-based IPC),
        // or from the legacy in-process static fields (kept for backward compat until
        // all callers are migrated).
        String pending = helium314.keyboard.latin.ai.PendingInsertBridge.consumeInsert(this);
        if (pending == null) {
            // Fallback: check legacy static field (same-process callers)
            pending = sPendingInsert;
            sPendingInsert = null;
        }
        if (pending != null) {
            final String text = pending;
            mHandler.postDelayed(() -> mInputLogic.mConnection.commitText(text, 1), 100);
        }
        if (helium314.keyboard.latin.ai.PendingInsertBridge.consumeReopenFlag(this) || sPendingReopenAiDialog) {
            sPendingReopenAiDialog = false;
            mHandler.postDelayed(() -> showAiClipboardDialog(), 150);
        }
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        StatsUtils.onFinishInputView();
        mHandler.onFinishInputView(finishingInput);
        mStatsUtilsManager.onFinishInputView();
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(final InputMethodSubtype subtype) {
        // Note that the calling sequence of onCreate() and onCurrentInputMethodSubtypeChanged()
        // is not guaranteed. It may even be called at the same time on a different thread.
        if (subtype.hashCode() == 0x7000000f) {
            // For some reason sometimes the system wants to set the dummy subtype, which messes with the currently enabled subtype.
            // Now that the dummy subtype has a fixed id, we can easily avoid enabling it.
            return;
        }
        InputMethodSubtype oldSubtype = mRichImm.getCurrentSubtype().getRawSubtype();
        if (subtype.equals(oldSubtype)) {
            // onStartInput may be called more than once, resulting in duplicate subtype switches
            return;
        }

        mSubtypeState.onSubtypeChanged(oldSubtype, subtype);
        StatsUtils.onSubtypeChanged(oldSubtype, subtype);
        mRichImm.onSubtypeChanged(subtype);
        mInputLogic.onSubtypeChanged(SubtypeLocaleUtils.getCombiningRulesExtraValue(subtype),
                mSettings.getCurrent());
        loadKeyboard();
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setRtl(mRichImm.getCurrentSubtype().isRtlSubtype());
        }
        mSettings.saveSubtypeForApp(mRichImm.getCurrentSubtype(), getCurrentInputEditorInfo().packageName);
    }

    /** alias to onCurrentInputMethodSubtypeChanged with a better name, as it's also used for internal switching */
    public void switchToSubtype(final InputMethodSubtype subtype) {
        onCurrentInputMethodSubtypeChanged(subtype);
    }

    private void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);

        final RichInputMethodSubtype subtypeForApp = editorInfo == null
            ? null :
            mSettings.getSubtypeForApp(editorInfo.packageName);
        final List<Locale> hintLocales = EditorInfoCompatUtils.getHintLocales(editorInfo);
        final InputMethodSubtype subtypeForLocales = mSubtypeState.getSubtypeForLocales(mRichImm, hintLocales, subtypeForApp);
        if (subtypeForLocales != null) {
            // found a better subtype using hint locales and saved-per-app subtype, that we should switch to.
            mHandler.postSwitchLanguage(subtypeForLocales);
        }
    }

    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);

        // only for active gesture data gathering, remove when data gathering phase is done (end of 2026 latest)
        if (GestureDataGatheringKt.isInActiveGatheringMode(editorInfo)) {
            mDictionaryFacilitator = GestureDataGatheringKt.getGestureDataActiveFacilitator();
        } else {
            mDictionaryFacilitator = mOriginalDictionaryFacilitator;
        }
        GestureDataGatheringKt.showEndNotificationIfNecessary(this); // will do nothing for a long time
        mInputLogic.setFacilitator(mDictionaryFacilitator);

        mDictionaryFacilitator.onStartInput();
        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
        mRichImm.refreshSubtypeCaches();
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.updateKeyboardTheme(mDisplayContext);
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }
        Log.i(TAG, (restarting ? "Res" : "S") +"tarting input. Cursor position = " + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd);
        if (DebugFlags.DEBUG_ENABLED) {
            EditorInfoCompatUtils.INSTANCE.debugLog(editorInfo, TAG);
        }

        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return;
        }

        // Update to a gesture consumer with the current editor and IME state.
        mGestureConsumer = GestureConsumer.newInstance(editorInfo,
                mInputLogic.getPrivateCommandPerformer(),
                mRichImm.getCurrentSubtypeLocale(),
                switcher.getKeyboard());

        // Forward this event to the accessibility utilities, if enabled.
        final AccessibilityUtils accessUtils = AccessibilityUtils.Companion.getInstance();
        if (accessUtils.isTouchExplorationEnabled()) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting);
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        StatsUtils.onStartInputView(editorInfo.inputType,
                Settings.getValues().mDisplayOrientation,
                !isDifferentTextField);

        // Proactively check cloud fallback on a background thread so model prefs
        // are up-to-date before the user presses any shortcut key.
        final android.content.SharedPreferences fallbackPrefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this);
        if (fallbackPrefs.getBoolean(Settings.PREF_AI_CLOUD_FALLBACK, helium314.keyboard.latin.settings.Defaults.PREF_AI_CLOUD_FALLBACK)) {
            final LatinIME ime = this;
            new Thread(() -> {
                helium314.keyboard.latin.ai.AiServiceSync.checkCloudFallback(fallbackPrefs);
                // Refresh toolbar badges on UI thread after probe completes
                ime.mHandler.post(() -> {
                    if (hasSuggestionStripView()) {
                        mSuggestionStripView.refreshCloudFallbackBadges();
                    }
                });
            }).start();
        }

        // Pulse AI toolbar keys if no AI provider is configured
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setAiSetupHint(!mInputLogic.isAnyAiConfigured());
        }

        // The EditorInfo might have a flag that affects fullscreen mode.
        // Note: This call should be done by InputMethodService?
        updateFullscreenMode();

        // we need to reload the setting before using them, e.g. in startInput or in postResumeSuggestions
        if (isDifferentTextField || !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings();
            currentSettingsValues = mSettings.getCurrent();
            if (hasSuggestionStripView())
                mSuggestionStripView.updateVoiceKey();
        }
        // ALERT: settings have not been reloaded and there is a chance they may be stale.
        // In the practice, if it is, we should have gotten onConfigurationChanged so it should
        // be fine, but this is horribly confusing and must be fixed AS SOON AS POSSIBLE.

        // In some cases the input connection has not been reset yet and we can't access it. In
        // this case we will need to call loadKeyboard() later, when it's accessible, so that we
        // can go into the correct mode, so we need to do some housekeeping here.
        final boolean needToCallLoadKeyboardLater;
        final Suggest suggest = mInputLogic.mSuggest;
        if (!isImeSuppressedByHardwareKeyboard()) {
            // The app calling setText() has the effect of clearing the composing
            // span, so we should reset our state unconditionally, even if restarting is true.
            // We also tell the input logic about the combining rules for the current subtype, so
            // it can adjust its combiners if needed.
            mInputLogic.startInput(mRichImm.getCombiningRulesExtraValueOfCurrentSubtype(), currentSettingsValues);

            resetDictionaryFacilitatorIfNecessary();

            // TODO[IL]: Can the following be moved to InputLogic#startInput?
            if (!mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(
                    editorInfo.initialSelStart, editorInfo.initialSelEnd,
                    false /* shouldFinishComposition */)) {
                // Sometimes, while rotating, for some reason the framework tells the app we are not
                // connected to it and that means we can't refresh the cache. In this case, schedule
                // a refresh later.
                // We try resetting the caches up to 5 times before giving up.
                mHandler.postResetCaches(isDifferentTextField, 5 /* remainingTries */);
                // mLastSelection{Start,End} are reset later in this method, no need to do it here
                needToCallLoadKeyboardLater = true;
            } else {
                // When rotating, and when input is starting again in a field from where the focus
                // didn't move (the keyboard having been closed with the back key),
                // initialSelStart and initialSelEnd sometimes are lying. Make a best effort to
                // work around this bug.
                mInputLogic.mConnection.tryFixIncorrectCursorPosition();
                if (mInputLogic.mConnection.isCursorTouchingWord(currentSettingsValues.mSpacingAndPunctuations, true)) {
                    mHandler.postResumeSuggestions(true /* shouldDelay */);
                }
                needToCallLoadKeyboardLater = false;
            }
        } else {
            // If we have a hardware keyboard we don't need to call loadKeyboard later anyway.
            needToCallLoadKeyboardLater = false;
        }

        if (isDifferentTextField) {
            mainKeyboardView.closing();
            suggest.setAutoCorrectionThreshold(currentSettingsValues.mAutoCorrectionThreshold);
            switcher.reloadMainKeyboard();
            if (needToCallLoadKeyboardLater) {
                // If we need to call loadKeyboard again later, we need to save its state now. The
                // later call will be done in #retryResetCaches.
                switcher.saveKeyboardState();
            }
        } else if (restarting) {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
            // In apps like Talk, we come here when the text is sent and the field gets emptied and
            // we need to re-evaluate the shift state, but not the whole layout which would be
            // disruptive.
            // Space state must be updated before calling updateShiftState
            switcher.requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
        // Set neutral suggestions and show the toolbar if the "Auto show toolbar" setting is enabled.
        if (!mHandler.hasPendingResumeSuggestions()) {
            mHandler.cancelUpdateSuggestionStrip();
            setNeutralSuggestionStrip();
            if (hasSuggestionStripView() && currentSettingsValues.mAutoShowToolbar && !tryShowClipboardSuggestion()) {
                mSuggestionStripView.setToolbarVisibility(true);
            }
        }

        mainKeyboardView.setMainDictionaryAvailability(mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary());
        mainKeyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(currentSettingsValues.mSlidingKeyInputPreviewEnabled);
        mainKeyboardView.setGestureHandlingEnabledByUser(
                currentSettingsValues.mGestureInputEnabled,
                currentSettingsValues.mGestureTrailEnabled,
                currentSettingsValues.mGestureFloatingPreviewTextEnabled);

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown()) {
            setNavigationBarColor();
            workaroundForHuaweiStatusBarIssue();
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        Log.i(TAG, "onWindowHidden");
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        clearNavigationBarColor();
    }

    void onFinishInputInternal() {
        super.onFinishInput();
        Log.i(TAG, "onFinishInput");

        mDictionaryFacilitator.onFinishInput();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputViewInternal(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        Log.i(TAG, "onFinishInputView");
        cleanupInternalStateForFinishInput();
    }

    private void cleanupInternalStateForFinishInput() {
        // Remove pending messages related to update suggestions
        mHandler.cancelUpdateSuggestionStrip();
        // Should do the following in onFinishInputInternal but until JB MR2 it's not called :(
        mInputLogic.finishInput();
        mKeyboardActionListener.resetMetaState();
    }

    protected void deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory();
    }

    @Override
    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
                                  final int newSelStart, final int newSelEnd,
                                  final int composingSpanStart, final int composingSpanEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd);
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose=" + oldSelEnd
                    + ", nss=" + newSelStart + ", nse=" + newSelEnd
                    + ", cs=" + composingSpanStart + ", ce=" + composingSpanEnd);
        }

        // This call happens whether our view is displayed or not, but if it's not then we should
        // not attempt recorrection. This is true even with a hardware keyboard connected: if the
        // view is not displayed we have no means of showing suggestions anyway, and if it is then
        // we want to show suggestions anyway.
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (isInputViewShown()
                && mInputLogic.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd, settingsValues)) {
            // we don't want to update a manually set shift state if selection changed towards one side
            // because this may end the manual shift, which is unwanted in case of shift + arrow keys for changing selection
            // todo: this is not fully implemented yet, and maybe should be behind a setting
            if (mKeyboardSwitcher.getKeyboard() != null && mKeyboardSwitcher.getKeyboard().mId.isAlphabetShiftedManually()
                && ((oldSelEnd == newSelEnd && oldSelStart != newSelStart) || (oldSelEnd != newSelEnd && oldSelStart == newSelStart)))
                return;
            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
    }

    /**
     * This is called when the user has clicked on the extracted text view,
     * when running in fullscreen mode.  The default implementation hides
     * the suggestions view when this happens, but only if the extracted text
     * editor has a vertical scroll bar because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    @Override
    public void onExtractedTextClicked() {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }

        super.onExtractedTextClicked();
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode.  The default
     * implementation hides the suggestions view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    @Override
    public void onExtractedCursorMovement(final int dx, final int dy) {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }

        super.onExtractedCursorMovement(dx, dy);
    }

    @Override
    public void hideWindow() {
        Log.i(TAG, "hideWindow");
        if (hasSuggestionStripView() && mSettings.getCurrent().mToolbarMode == ToolbarMode.EXPANDABLE)
            mSuggestionStripView.setToolbarVisibility(false);
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        if (isShowingOptionDialog()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        if (mActiveDialog != null && mActiveDialog.isShowing()) {
            mActiveDialog.dismiss();
        }
        super.hideWindow();
    }

    @Override
    public void requestHideSelf(int flags) {
        super.requestHideSelf(flags);
        Log.i(TAG, "requestHideSelf: " + flags);
    }

    @Override
    public void onSwipeDownOnToolbar() {
        requestHideSelf(0);
    }

    @Override
    public void onDisplayCompletions(final CompletionInfo[] applicationSpecifiedCompletions) {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "Received completions:");
            if (applicationSpecifiedCompletions != null) {
                for (int i = 0; i < applicationSpecifiedCompletions.length; i++) {
                    Log.i(TAG, "  #" + i + ": " + applicationSpecifiedCompletions[i]);
                }
            }
        }
        if (!mSettings.getCurrent().isApplicationSpecifiedCompletionsOn()) {
            return;
        }
        // If we have an update request in flight, we need to cancel it so it does not override
        // these completions.
        mHandler.cancelUpdateSuggestionStrip();
        if (applicationSpecifiedCompletions == null) {
            setNeutralSuggestionStrip();
            return;
        }

        final ArrayList<SuggestedWords.SuggestedWordInfo> applicationSuggestedWords =
                SuggestedWords.getFromApplicationSpecifiedCompletions(
                        applicationSpecifiedCompletions);
        final SuggestedWords suggestedWords = new SuggestedWords(applicationSuggestedWords,
                null /* rawSuggestions */,
                null /* typedWord */,
                false /* typedWordValid */,
                false /* willAutoCorrect */,
                false /* isObsoleteSuggestions */,
                SuggestedWords.INPUT_STYLE_APPLICATION_SPECIFIED /* inputStyle */,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER);
        // When in fullscreen mode, show completions generated by the application forcibly
        setSuggestedWords(suggestedWords);
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        // This method may be called before {@link #setInputView(View)}.
        if (mInputView == null) {
            return;
        }
        final View visibleKeyboardView = mKeyboardSwitcher.getWrapperView();
        if (visibleKeyboardView == null) {
            return;
        }
        final int inputHeight = mInputView.getHeight();
        if (isImeSuppressedByHardwareKeyboard() && !visibleKeyboardView.isShown()) {
            // If there is a hardware keyboard and a visible software keyboard view has been hidden,
            // no visual element will be shown on the screen.
            // for some reason setting contentTopInsets and visibleTopInsets broke somewhere along the
            // way from OpenBoard to HeliBoard (GH-702, GH-1455), but not setting anything seems to work
            mInsetsUpdater.setInsets(outInsets);
            return;
        }
        final int stripHeight = mKeyboardSwitcher.isShowingStripContainer() ? mKeyboardSwitcher.getStripContainer().getHeight() : 0;
        int visibleTopY = inputHeight - visibleKeyboardView.getHeight() - stripHeight;

        if (hasSuggestionStripView()) {
            mSuggestionStripView.setMoreSuggestionsHeight(visibleTopY);
        }

        // Need to set expanded touchable region only if a keyboard view is being shown.
        if (visibleKeyboardView.isShown()) {
            final int touchLeft = 0;
            final int touchTop = mKeyboardSwitcher.isShowingPopupKeysPanel() ? 0 : visibleTopY;
            final int touchRight = visibleKeyboardView.getWidth();
            final int touchBottom = inputHeight
                    // Extend touchable region below the keyboard.
                    + EXTENDED_TOUCHABLE_REGION_HEIGHT;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.set(touchLeft, touchTop, touchRight, touchBottom);
        }

        // Has to be subtracted after calculating touchableRegion
        visibleTopY -= getEmojiSearchActivityHeight();

        outInsets.contentTopInsets = visibleTopY;
        outInsets.visibleTopInsets = visibleTopY;
        mInsetsUpdater.setInsets(outInsets);
    }

    public void startShowingInputView(final boolean needsToLoadKeyboard) {
        mIsExecutingStartShowingInputView = true;
        // This {@link #showWindow(boolean)} will eventually call back
        // {@link #onEvaluateInputViewShown()}.
        showWindow(true /* showInput */);
        mIsExecutingStartShowingInputView = false;
        if (needsToLoadKeyboard) {
            loadKeyboard();
        }
    }

    public void stopShowingInputView() {
        showWindow(false /* showInput */);
    }

    @Override
    public boolean onShowInputRequested(final int flags, final boolean configChange) {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true;
        }
        return super.onShowInputRequested(flags, configChange);
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        if (mIsExecutingStartShowingInputView) {
            return true;
        }
        return super.onEvaluateInputViewShown();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        if (isImeSuppressedByHardwareKeyboard()) {
            // If there is a hardware keyboard, disable full screen mode.
            return false;
        }
        // Reread resource value here, because this method is called by the framework as needed.
        final boolean isFullscreenModeAllowed = Settings.readFullscreenModeAllowed(getResources());
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            // TODO: Remove this hack. Actually we should not really assume NO_EXTRACT_UI
            // implies NO_FULLSCREEN. However, the framework mistakenly does.  i.e. NO_EXTRACT_UI
            // without NO_FULLSCREEN doesn't work as expected. Because of this we need this
            // hack for now.  Let's get rid of this once the framework gets fixed.
            final EditorInfo ei = getCurrentInputEditorInfo();
            if (ei == null) return false;
            final boolean noExtractUi = (ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0;
            final boolean noFullscreen = (ei.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0;
            if (noExtractUi || noFullscreen) return false;
            if (mKeyboardSwitcher.getVisibleKeyboardView() == null || mSuggestionStripView == null) return false;
            final int usedHeight = mKeyboardSwitcher.getVisibleKeyboardView().getHeight() + mSuggestionStripView.getHeight();
            final int availableHeight = getResources().getDisplayMetrics().heightPixels;
            return usedHeight > availableHeight * 0.6; // if we have less than 40% available, use fullscreen mode
        }
        return false;
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();
        KtxKt.updateSoftInputWindowLayoutParameters(this, mInputView);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.R)
    public InlineSuggestionsRequest onCreateInlineSuggestionsRequest(@NonNull Bundle uiExtras) {
        Log.d(TAG,"onCreateInlineSuggestionsRequest called");
        if (Settings.getValues().mSuggestionStripHiddenPerUserSettings) {
            return null;
        }

        return InlineAutofillUtils.createInlineSuggestionRequest(mDisplayContext);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.R)
    public boolean onInlineSuggestionsResponse(InlineSuggestionsResponse response) {
        Log.d(TAG,"onInlineSuggestionsResponse called");
        if (Settings.getValues().mSuggestionStripHiddenPerUserSettings) {
            return false;
        }

        final List<InlineSuggestion> inlineSuggestions = response.getInlineSuggestions();
        if (inlineSuggestions.isEmpty()) {
            return false;
        }

        final View inlineSuggestionView = InlineAutofillUtils.createView(inlineSuggestions, mDisplayContext);

        // Without this function the inline autofill suggestions will not be visible
        mHandler.cancelResumeSuggestions();

        mSuggestionStripView.setExternalSuggestionView(inlineSuggestionView, true);

        return true;
    }

    public int getCurrentAutoCapsState() {
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent());
    }

    @Nullable
    public RecapitalizeMode getCurrentRecapitalizeState() {
        return mInputLogic.getCurrentRecapitalizeState();
    }

    /**
     * @param codePoints code points to get coordinates for.
     * @return x,y coordinates for this keyboard, as a flattened array.
     */
    public int[] getCoordinatesForCurrentKeyboard(final int[] codePoints) {
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        if (null == keyboard) {
            return CoordinateUtils.newCoordinateArray(codePoints.length,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE);
        }
        return keyboard.getCoordinates(codePoints);
    }

    public void displaySettingsDialog() {
        launchSettings();
    }

    public void setAiProcessing(boolean processing) {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setAiProcessing(processing, null);
        }
    }

    public void setAiProcessing(boolean processing, int slotNumber) {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        if (mSuggestionStripView != null) {
            helium314.keyboard.latin.utils.ToolbarKey key = slotNumber == 0
                ? helium314.keyboard.latin.utils.ToolbarKey.AI_ASSIST
                : helium314.keyboard.latin.utils.ToolbarKey.valueOf("AI_SLOT_" + slotNumber);
            mSuggestionStripView.setAiProcessing(processing, key);
        }
    }

    public void setAiProcessingForKey(boolean processing, helium314.keyboard.latin.utils.ToolbarKey key) {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setAiProcessing(processing, key);
        }
    }

    private String[][] loadToneChips() {
        android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this);
        String json = prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_AI_TONE_CHIPS,
            helium314.keyboard.latin.settings.Defaults.PREF_AI_TONE_CHIPS);
        if (json == null || json.equals("[]")) json = helium314.keyboard.latin.settings.Defaults.PREF_AI_TONE_CHIPS;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            String[][] chips = new String[arr.length()][2];
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                chips[i][0] = obj.getString("name");
                chips[i][1] = obj.getString("prompt");
            }
            return chips;
        } catch (Exception e) {
            return new String[][]{{"Formal", "Rewrite this text in a more formal and professional tone. Return only the rewritten text, nothing else."}};
        }
    }

    public void showAiPreview(String text) {
        showAiPreview(text, null);
    }

    public void showAiPreview(String text, String clipboardContext) {
        KeyboardSwitcher.getInstance().showAiPreview(text);
        // Show clipboard context indicator
        View panel = KeyboardSwitcher.getInstance().getAiPreviewPanel();
        if (panel != null) {
            android.widget.TextView contextLabel = panel.findViewById(R.id.ai_preview_context_label);
            if (contextLabel != null) {
                if (clipboardContext != null && !clipboardContext.isEmpty()) {
                    String preview = clipboardContext.replace('\n', ' ');
                    if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
                    contextLabel.setText("\u2715  \uD83D\uDCCB  " + preview);
                    contextLabel.setVisibility(View.VISIBLE);
                    contextLabel.setClickable(true);
                    contextLabel.setFocusable(true);
                    // Fade in
                    contextLabel.setAlpha(0f);
                    contextLabel.animate().alpha(1f).setDuration(400).start();
                    // Dismiss clipboard context on click
                    contextLabel.setOnClickListener(cv -> {
                        contextLabel.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                            contextLabel.setVisibility(View.GONE);
                        }).start();
                        // Remember dismissed text so we don't show it again
                        mDismissedClipboardText = clipboardContext;
                        // Clear the stored context so chips won't use it
                        mLastClipboardContext = null;
                    });
                    mLastClipboardContext = clipboardContext;
                } else {
                    contextLabel.setVisibility(View.GONE);
                    mLastClipboardContext = null;
                }
            }
        }
    }

    public void showAiPreviewInstant(String text) {
        KeyboardSwitcher.getInstance().showAiPreviewInstant(text);
    }

    public void hideAiPreview() {
        KeyboardSwitcher.getInstance().hideAiPreview();
    }

    public void startAiShimmer() {
        KeyboardSwitcher.getInstance().startShimmer();
    }

    public void stopAiShimmer() {
        KeyboardSwitcher.getInstance().stopShimmer();
    }

    public void setupAiPreviewButtons(Runnable onApply, Runnable onRetry, Runnable onDismiss, Runnable onEdit, String resultText) {
        setupAiPreviewButtons(onApply, onRetry, onDismiss, onEdit, resultText, null);
    }

    public void setupAiPreviewButtons(Runnable onApply, Runnable onRetry, Runnable onDismiss, Runnable onEdit, String resultText, String clipboardContext) {
        View panel = KeyboardSwitcher.getInstance().getAiPreviewPanel();
        if (panel == null) return;
        View applyBtn = panel.findViewById(R.id.ai_preview_apply);
        applyBtn.setOnClickListener(v -> {
            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                onApply.run();
            }).start();
        });
        panel.findViewById(R.id.ai_preview_retry).setOnClickListener(v -> onRetry.run());
        panel.findViewById(R.id.ai_preview_dismiss).setOnClickListener(v -> onDismiss.run());
        panel.findViewById(R.id.ai_preview_edit).setOnClickListener(v -> onEdit.run());
        panel.findViewById(R.id.ai_preview_copy).setOnClickListener(v -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null) cb.setPrimaryClip(android.content.ClipData.newPlainText("AI result", resultText));
            android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
        });

        // Setup tone chips
        android.widget.LinearLayout chipsContainer = panel.findViewById(R.id.ai_preview_chips);
        chipsContainer.removeAllViews();
        final String[] currentResult = {resultText};
        // Theme-aware chip colors
        int chipTextColor = helium314.keyboard.latin.settings.Settings.getValues().mColors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT);
        int chipStrokeColor = (chipTextColor & 0x00FFFFFF) | 0x40000000; // 25% alpha of text color
        int chipFillColor = (chipTextColor & 0x00FFFFFF) | 0x1A000000;   // 10% alpha of text color
        for (String[] tone : loadToneChips()) {
            android.widget.TextView chip = new android.widget.TextView(this);
            chip.setText(tone[0]);
            chip.setTextSize(13);
            chip.setTextColor((chipTextColor & 0x00FFFFFF) | 0xB3000000);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(1), chipStrokeColor);
            bg.setColor(chipFillColor);
            chip.setBackground(bg);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMarginEnd(dp(6));
            chip.setLayoutParams(lp);
            chip.setClickable(true);
            chip.setFocusable(true);
            final String tonePrompt = tone[1];
            chip.setOnClickListener(v -> {
                // Bounce animation on press
                chip.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() ->
                    chip.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                ).start();
                // Highlight selected chip, dim others
                for (int i = 0; i < chipsContainer.getChildCount(); i++) {
                    View c = chipsContainer.getChildAt(i);
                    c.setAlpha(c == chip ? 1f : 0.4f);
                    c.setEnabled(false);
                }
                // Build prompt with clipboard context if available (and not dismissed)
                String fullPrompt = tonePrompt;
                if (mLastClipboardContext != null && !mLastClipboardContext.isEmpty()) {
                    fullPrompt = tonePrompt
                        + "\n\nThe user is replying to the following message. Use it to understand the context and adjust the reply accordingly, but ONLY return the rewritten version of the user's text. Do NOT include the original message or any additional commentary."
                        + "\n\nMessage being replied to:\n\"\"\"" + mLastClipboardContext + "\"\"\"";
                }
                final String fFullPrompt = fullPrompt;
                // Track last tone for retry
                mLastTonePrompt = fFullPrompt;
                mLastToneInput = currentResult[0];
                // Start shimmer while processing
                startAiShimmer();
                android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this);
                new Thread(() -> {
                    String adjusted = helium314.keyboard.latin.ai.AiServiceSync.processInline(
                        currentResult[0], fFullPrompt, prefs, null);
                    mHandler.post(() -> {
                        stopAiShimmer();
                        // Re-enable all chips
                        for (int i = 0; i < chipsContainer.getChildCount(); i++) {
                            View c = chipsContainer.getChildAt(i);
                            c.setAlpha(1f);
                            c.setEnabled(true);
                        }
                        currentResult[0] = adjusted;
                        // Typewriter the new result
                        showAiPreviewInstant(adjusted);
                        // Update apply and copy to use new result
                        panel.findViewById(R.id.ai_preview_apply).setOnClickListener(av -> {
                            onApply.run();
                        });
                        panel.findViewById(R.id.ai_preview_copy).setOnClickListener(cv -> {
                            android.content.ClipboardManager cb2 = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cb2 != null) cb2.setPrimaryClip(android.content.ClipData.newPlainText("AI result", currentResult[0]));
                            android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
                        });
                    });
                }).start();
            });
            chipsContainer.addView(chip);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private String mLastTonePrompt;
    private String mLastToneInput;
    private String mLastClipboardContext;
    private String mDismissedClipboardText;

    public String getDismissedClipboardText() { return mDismissedClipboardText; }

    public String getLastTonePrompt() { return mLastTonePrompt; }
    public String getLastToneInput() { return mLastToneInput; }

    private android.speech.SpeechRecognizer mSpeechRecognizer;
    private helium314.keyboard.latin.ai.WhisperRecorder mWhisperRecorder;
    private volatile boolean mWhisperTranscribing = false;
    private volatile long mAiVoiceCancelGraceUntil = 0L;
    private boolean mAiVoiceListening = false;
    private boolean mAiVoiceLocaleRetry = false;
    private android.os.Handler mAiVoiceWatchdogHandler;
    private Runnable mAiVoiceWatchdogRunnable;

    /**
     * Show a voice error to the user. Tries Toast first, but ALSO commits an inline marker
     * to the input field, because users can disable toasts for the keyboard package at the OS
     * level (NotificationService "Suppressing toast ... by user request"), in which case Toast
     * is the only feedback they would otherwise have.
     */
    private void showVoiceError(String msg) {
        android.util.Log.e("LatinIME", "Voice error: " + msg);
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
        try {
            android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText("[voice: " + msg + "]", 1);
            }
        } catch (Exception ignored) {}
    }

    private void cancelAiVoiceWatchdog() {
        if (mAiVoiceWatchdogHandler != null && mAiVoiceWatchdogRunnable != null) {
            mAiVoiceWatchdogHandler.removeCallbacks(mAiVoiceWatchdogRunnable);
        }
        mAiVoiceWatchdogRunnable = null;
    }

    private void armAiVoiceWatchdog(long timeoutMs) {
        cancelAiVoiceWatchdog();
        if (mAiVoiceWatchdogHandler == null) {
            mAiVoiceWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        mAiVoiceWatchdogRunnable = new Runnable() {
            @Override public void run() {
                // Fired only if neither onReadyForSpeech nor onError ever arrived.
                android.util.Log.e("LatinIME", "AI voice watchdog fired - speech service unresponsive");
                mAiVoiceListening = false;
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAiProcessing(false, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
                }
                cleanupSpeechRecognizer();
                showVoiceError("speech service unresponsive (no language pack? try Whisper)");
            }
        };
        mAiVoiceWatchdogHandler.postDelayed(mAiVoiceWatchdogRunnable, timeoutMs);
    }
    public void showAiVoiceModeDialog() {
        helium314.keyboard.latin.ai.AiDialogComponentsKt.showAiVoiceModeDialog(this);
    }

    public void showMarkAllRemindersReadDialog() {
        helium314.keyboard.latin.ai.AiDialogComponentsKt.showMarkAllRemindersReadDialog(this);
    }

    public void refreshReminderAccent() {
        try {
            if (mSuggestionStripView != null) mSuggestionStripView.refreshReminderAccent();
        } catch (Exception ignored) {}
    }

    public String getAiVoiceModeInstruction() {
        final android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this);

        // Check if reply-to-clipboard mode was triggered
        if (prefs.getBoolean("ai_voice_reply_mode", false)) {
            prefs.edit().putBoolean("ai_voice_reply_mode", false).apply();
            return helium314.keyboard.latin.settings.Defaults.AI_VOICE_REPLY_PROMPT;
        }

        final int mode = prefs.getInt(Settings.PREF_AI_VOICE_MODE, helium314.keyboard.latin.settings.Defaults.PREF_AI_VOICE_MODE);
        final String[] defaultPrompts = helium314.keyboard.latin.settings.Defaults.INSTANCE.getAI_VOICE_MODE_PROMPTS();
        final int builtinCount = defaultPrompts.length;

        if (mode < builtinCount) {
            String custom = prefs.getString("ai_voice_prompt_" + mode, null);
            if (custom != null && !custom.isEmpty()) return custom;
            return defaultPrompts[mode];
        } else {
            // Custom mode
            try {
                org.json.JSONArray arr = new org.json.JSONArray(prefs.getString(Settings.PREF_AI_VOICE_CUSTOM_MODES, "[]"));
                int ci = mode - builtinCount;
                if (ci < arr.length()) {
                    return arr.getJSONObject(ci).getString("prompt");
                }
            } catch (org.json.JSONException ignored) {}
            return defaultPrompts[0];
        }
    }

    public String getAiVoiceModel() {
        final android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this);
        helium314.keyboard.latin.ai.AiServiceSync.checkCloudFallback(prefs);
        String voiceModel = prefs.getString(Settings.PREF_AI_VOICE_MODEL, "");
        if (voiceModel == null || voiceModel.isEmpty()) {
            return prefs.getString(Settings.PREF_AI_MODEL, helium314.keyboard.latin.settings.Defaults.PREF_AI_MODEL);
        }
        return voiceModel;
    }

    public void startAiVoiceRecognition() {
        android.util.Log.d("LatinIME", "startAiVoiceRecognition: mWhisperRecorder=" + mWhisperRecorder + ", mAiVoiceListening=" + mAiVoiceListening + ", mWhisperTranscribing=" + mWhisperTranscribing);

        // Active Whisper recording → stop and transcribe (always highest priority,
        // recording finish must never be intercepted by a cancel-check).
        if (mWhisperRecorder != null) {
            android.util.Log.d("LatinIME", "Whisper recorder exists, stopping and transcribing");
            stopWhisperAndTranscribe();
            return;
        }

        // Google SpeechRecognizer stop check
        if (mAiVoiceListening) {
            stopAiVoiceRecognition();
            return;
        }

        // Hard cancel: if an AI_VOICE call (transcribe or post-AI) is in flight, cancel it.
        // Grace period after recording-stop prevents accidental double-tap from cancelling.
        if (helium314.keyboard.latin.ai.AiCancelRegistry.getActiveKey()
                == helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE) {
            if (android.os.SystemClock.uptimeMillis() < mAiVoiceCancelGraceUntil) {
                android.util.Log.d("LatinIME", "AI_VOICE tap within grace period, ignoring");
                return;
            }
            helium314.keyboard.latin.ai.AiCancelRegistry.cancel();
            return;
        }

        // Block taps while Whisper transcription is in progress (defensive)
        if (mWhisperTranscribing) {
            android.util.Log.d("LatinIME", "Whisper transcribing, ignoring tap");
            return;
        }

        // START new recording based on engine preference
        final android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this);
        final String engine = prefs.getString(Settings.PREF_AI_VOICE_ENGINE, helium314.keyboard.latin.settings.Defaults.PREF_AI_VOICE_ENGINE);
        android.util.Log.d("LatinIME", "Starting voice recognition, engine=" + engine);

        if ("whisper".equals(engine)) {
            startWhisperRecording();
            return;
        }

        // Check permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    android.content.Intent intent = new android.content.Intent(this, helium314.keyboard.latin.ai.VoiceTrampolineActivity.class);
                    intent.putExtra(helium314.keyboard.latin.ai.VoiceTrampolineActivity.EXTRA_VOICE_ACTION,
                            helium314.keyboard.latin.ai.VoiceTrampolineActivity.ACTION_REQUEST_MIC);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
                return;
            }
        }

        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this, "Speech recognition not available on this device", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        try {
        mSpeechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Speech init failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (mSpeechRecognizer == null) {
            android.widget.Toast.makeText(this, "Speech recognizer unavailable", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        mSpeechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                cancelAiVoiceWatchdog();
                mAiVoiceListening = true;
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAiProcessing(true, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
                }
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                mAiVoiceListening = false;
            }
            @Override public void onError(int error) {
                cancelAiVoiceWatchdog();
                mAiVoiceListening = false;
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAiProcessing(false, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
                }
                // Auto-retry once without forcing the locale if the requested language pack
                // is missing or unsupported. Some Samsung devices fail with ERROR_CLIENT (5)
                // instead of ERROR_LANGUAGE_NOT_SUPPORTED (12) when the Soda offline pack
                // for the hint locale is absent.
                final int ERROR_LANGUAGE_NOT_SUPPORTED = 12;
                final int ERROR_LANGUAGE_UNAVAILABLE = 13;
                if (!mAiVoiceLocaleRetry && (error == ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == ERROR_LANGUAGE_UNAVAILABLE
                        || error == android.speech.SpeechRecognizer.ERROR_CLIENT)) {
                    android.util.Log.w("LatinIME", "Voice error " + error + " - retrying without forced locale");
                    mAiVoiceLocaleRetry = true;
                    cleanupSpeechRecognizer();
                    startAiVoiceRecognition();
                    return;
                }
                String msg = "voice error (" + error + ")";
                switch (error) {
                    case android.speech.SpeechRecognizer.ERROR_NO_MATCH: msg = "no speech detected"; break;
                    case android.speech.SpeechRecognizer.ERROR_NETWORK: msg = "network error (offline mode needs language pack)"; break;
                    case android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "network timeout"; break;
                    case android.speech.SpeechRecognizer.ERROR_AUDIO: msg = "audio recording error"; break;
                    case android.speech.SpeechRecognizer.ERROR_SERVER: msg = "server error"; break;
                    case android.speech.SpeechRecognizer.ERROR_CLIENT: msg = "client error (language pack missing?)"; break;
                    case android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "microphone permission denied"; break;
                    case ERROR_LANGUAGE_NOT_SUPPORTED: msg = "language not supported (install language pack)"; break;
                    case ERROR_LANGUAGE_UNAVAILABLE: msg = "language unavailable (install language pack)"; break;
                }
                showVoiceError(msg);
                cleanupSpeechRecognizer();
            }
            @Override public void onResults(Bundle results) {
                cancelAiVoiceWatchdog();
                mAiVoiceListening = false;
                mAiVoiceLocaleRetry = false;
                java.util.ArrayList<String> matches = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                cleanupSpeechRecognizer();
                // Always restore voice icon (stop → mic)
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAiProcessing(false, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
                }
                if (matches != null && !matches.isEmpty()) {
                    String rawTranscription = matches.get(0);
                    mInputLogic.handleAiVoiceResult(rawTranscription);
                } else {
                    showVoiceError("no speech detected");
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        android.content.Intent intent = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        // Skip forced locale on retry, so the recognizer falls back to its default.
        if (!mAiVoiceLocaleRetry) {
            try {
                android.view.inputmethod.EditorInfo ei = getCurrentInputEditorInfo();
                if (ei != null && ei.hintLocales != null && ei.hintLocales.size() > 0) {
                    intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, ei.hintLocales.get(0).toLanguageTag());
                }
            } catch (Exception ignored) {}
        }
        try {
            mSpeechRecognizer.startListening(intent);
            // Watchdog: if neither onReadyForSpeech nor onError fires within 8s, the
            // remote speech service is silently broken (seen on Samsung when offline
            // language pack is missing). Force an inline error in that case.
            armAiVoiceWatchdog(8000L);
        } catch (Exception e) {
            android.util.Log.e("LatinIME", "startListening failed", e);
            mAiVoiceListening = false;
            if (mSuggestionStripView != null) {
                mSuggestionStripView.setAiProcessing(false, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
            }
            cleanupSpeechRecognizer();
            showVoiceError("voice start failed: " + e.getMessage());
        }
    }

    public void stopAiVoiceRecognition() {
        cancelAiVoiceWatchdog();
        mAiVoiceLocaleRetry = false;
        mAiVoiceListening = false;
        mWhisperTranscribing = false;
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setAiVoiceRecording(false);
            mSuggestionStripView.setAiProcessing(false, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
        }
        cleanupSpeechRecognizer();
        if (mWhisperRecorder != null) {
            mWhisperRecorder.release();
            mWhisperRecorder = null;
        }
    }

    private void cleanupSpeechRecognizer() {
        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.stopListening();
                mSpeechRecognizer.destroy();
            } catch (Exception ignored) {}
            mSpeechRecognizer = null;
        }
    }

    private void stopWhisperAndTranscribe() {
        android.util.Log.d("LatinIME", "stopWhisperAndTranscribe called");
        final long whisperEntryMs = android.os.SystemClock.elapsedRealtime();
        android.util.Log.d("WhisperTiming", "stopWhisperAndTranscribe entry");
        // Immediate visual + state changes on the IME thread so the user gets feedback right away.
        final helium314.keyboard.latin.ai.WhisperRecorder recorder = mWhisperRecorder;
        mWhisperRecorder = null;
        mAiVoiceListening = false;
        mWhisperTranscribing = true;
        // Grace period: ignore taps for 600ms so a quick double-tap doesn't immediately cancel.
        mAiVoiceCancelGraceUntil = android.os.SystemClock.uptimeMillis() + 600L;
        // Switch from "recording" (REC icon, static) to "transcribing" (mic, pulsing).
        // setAiProcessing handles its own haptic.
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setAiVoiceRecording(false);
            mSuggestionStripView.setAiProcessing(true, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
        }
        // Register the cancel handle now so the user can cancel during the upload phase.
        final helium314.keyboard.latin.ai.AiCancelRegistry.CancelHandle whisperHandle =
            helium314.keyboard.latin.ai.AiCancelRegistry.INSTANCE.start(
                helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
        android.widget.Toast.makeText(this, "Bezig met transcriberen...", android.widget.Toast.LENGTH_SHORT).show();

        final android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this);
        new Thread(() -> {
            // Heavy work off the IME thread: stop recorder, write WAV, upload + transcribe.
            final java.io.File wavFile = recorder != null ? recorder.stop() : null;
            final long stopDoneMs = android.os.SystemClock.elapsedRealtime();
            android.util.Log.d("WhisperTiming", "tap_to_stop_done=" + (stopDoneMs - whisperEntryMs) + "ms wav_size=" + (wavFile != null ? wavFile.length() : -1) + "B");
            if (wavFile == null) {
                mHandler.post(() -> {
                    mWhisperTranscribing = false;
                    helium314.keyboard.latin.ai.AiCancelRegistry.INSTANCE.clear(whisperHandle);
                    if (mSuggestionStripView != null) {
                        mSuggestionStripView.setAiProcessing(false, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
                    }
                });
                return;
            }
            String transcription = helium314.keyboard.latin.ai.AiServiceSync.transcribeWithWhisper(wavFile, prefs, whisperHandle);
            mHandler.post(() -> {
                mWhisperTranscribing = false;
                boolean cancelled = helium314.keyboard.latin.ai.AiCancelRegistry.isCancelled(whisperHandle);
                helium314.keyboard.latin.ai.AiCancelRegistry.INSTANCE.clear(whisperHandle);
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAiProcessing(false, helium314.keyboard.latin.utils.ToolbarKey.AI_VOICE);
                }
                if (cancelled) return;
                if (transcription.startsWith("[Whisper")) {
                    android.widget.Toast.makeText(this, transcription, android.widget.Toast.LENGTH_LONG).show();
                } else {
                    mInputLogic.handleAiVoiceResult(transcription);
                }
            });
        }).start();
    }

    private void startWhisperRecording() {
        android.util.Log.d("LatinIME", "startWhisperRecording called");
        // Check permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    android.content.Intent intent = new android.content.Intent(this, helium314.keyboard.latin.ai.VoiceTrampolineActivity.class);
                    intent.putExtra(helium314.keyboard.latin.ai.VoiceTrampolineActivity.EXTRA_VOICE_ACTION,
                            helium314.keyboard.latin.ai.VoiceTrampolineActivity.ACTION_REQUEST_MIC);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
                return;
            }
        }

        mWhisperRecorder = new helium314.keyboard.latin.ai.WhisperRecorder(this);
        mWhisperRecorder.start();
        mAiVoiceListening = true;
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setAiVoiceRecording(true);
        }
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
    }

    public void setAiUndoAvailable(boolean available, int slotNumber) {
        if (mSuggestionStripView != null) {
            helium314.keyboard.latin.utils.ToolbarKey key = slotNumber == 0
                ? helium314.keyboard.latin.utils.ToolbarKey.AI_ASSIST
                : helium314.keyboard.latin.utils.ToolbarKey.valueOf("AI_SLOT_" + slotNumber);
            mSuggestionStripView.setAiUndoAvailable(available, key);
        }
    }

    public void setInlineInstructionMode(boolean active) {
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setInlineInstructionMode(active);
        }
        final helium314.keyboard.keyboard.MainKeyboardView keyboardView =
            helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.setInlineInstructionMode(active);
        }
    }

    public void showAiInstructionDialog() {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        helium314.keyboard.latin.ai.AiDialogComponentsKt.showAiInstructionDialog(this);
    }

    public void showSlotConfigDialog(int slotNumber) {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        helium314.keyboard.latin.ai.AiDialogComponentsKt.showSlotConfigDialog(this, slotNumber);
    }

    public void showAiClipboardDialog() {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        helium314.keyboard.latin.ai.AiDialogComponentsKt.showAiClipboardDialog(this);
    }

    public void showAiActionsDialog() {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        helium314.keyboard.latin.ai.AiDialogComponentsKt.showAiActionsDialog(this);
    }

    public void showAiConversationActivity() {
        AudioAndHapticFeedbackManager.getInstance().vibrate(20);
        android.content.Intent intent = new android.content.Intent(this,
                helium314.keyboard.latin.ai.ConversationActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    public boolean showInputPickerDialog() {
        if (isShowingOptionDialog()) return false;
        if (mRichImm.hasMultipleEnabledIMEsOrSubtypes(true)) {
            mOptionsDialog = InputMethodPickerKt.createInputMethodPickerDialog(this, mRichImm, mKeyboardSwitcher.getMainKeyboardView().getWindowToken());
            mOptionsDialog.show();
            return true;
        }
        return false;
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    // called when language switch key is pressed (either the keyboard key, or long-press comma)
    public void switchToNextSubtype() {
        final boolean switchSubtype = mSettings.getCurrent().mLanguageSwitchKeyToOtherSubtypes;
        final boolean switchIme = mSettings.getCurrent().mLanguageSwitchKeyToOtherImes;

        // switch IME if wanted and possible
        if (switchIme && !switchSubtype && ImeCompat.INSTANCE.switchInputMethod(this))
            return;
        final boolean hasMoreThanOneSubtype = mRichImm.hasMultipleEnabledSubtypesInThisIme(true);
        // switch subtype if wanted, do nothing if no other subtype is available
        if (switchSubtype && !switchIme) {
            if (hasMoreThanOneSubtype)
                // switch to previous subtype if current one was used, otherwise cycle through list
                mSubtypeState.switchSubtype(mRichImm);
            return;
        }
        // language key set to switch both, or language key is not shown on keyboard -> switch both
        if (hasMoreThanOneSubtype && mSubtypeState.getCurrentSubtypeHasBeenUsed()) {
            mSubtypeState.switchSubtype(mRichImm);
            return;
        }
        if (ImeCompat.INSTANCE.shouldSwitchToOtherInputMethods(this)) {
            final InputMethodSubtype nextSubtype = mRichImm.getNextSubtypeInThisIme(false);
            if (nextSubtype != null) {
                switchToSubtype(nextSubtype);
                return;
            } else if (ImeCompat.INSTANCE.switchInputMethod(this)) {
                return;
            }
        }
        mSubtypeState.switchSubtype(mRichImm);
    }

    // Implementation of {@link SuggestionStripView.Listener}.
    @Override
    public void onCodeInput(final int codePoint, final int x, final int y, final boolean isKeyRepeat) {
        mKeyboardActionListener.onCodeInput(codePoint, x, y, isKeyRepeat);
    }

    // This method is public for testability of LatinIME, but also in the future it should
    // completely replace #onCodeInput.
    public void onEvent(@NonNull final Event event) {
        if (KeyCode.VOICE_INPUT == event.getKeyCode()) {
            mRichImm.switchToShortcutIme(this);
        }
        final InputTransaction completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event,
                        mKeyboardSwitcher.getKeyboardShiftMode(),
                        mKeyboardSwitcher.getCurrentKeyboardScript(), mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    public void onTextInput(final String rawText) {
        // TODO: have the keyboard pass the correct key code when we need it.
        final Event event = Event.createSoftwareTextEvent(rawText, KeyCode.MULTIPLE_CODE_POINTS, null);
        final InputTransaction completeInputTransaction =
                mInputLogic.onTextInput(mSettings.getCurrent(), event,
                        mKeyboardSwitcher.getKeyboardShiftMode(), mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        mInputLogic.restartSuggestionsOnWordTouchedByCursor(mSettings.getCurrent(), mKeyboardSwitcher.getCurrentKeyboardScript());
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    public void onStartBatchInput() {
        mInputLogic.onStartBatchInput(mSettings.getCurrent(), mKeyboardSwitcher, mHandler);
        mGestureConsumer.onGestureStarted(mRichImm.getCurrentSubtypeLocale(), mKeyboardSwitcher.getKeyboard());
    }

    public void onUpdateBatchInput(final InputPointers batchPointers) {
        mInputLogic.onUpdateBatchInput(batchPointers);
    }

    public void onEndBatchInput(final InputPointers batchPointers) {
        mInputLogic.onEndBatchInput(batchPointers);
        mGestureConsumer.onGestureCompleted(batchPointers);
    }

    public void onCancelBatchInput() {
        mInputLogic.onCancelBatchInput(mHandler);
        mGestureConsumer.onGestureCanceled();
    }

    /**
     * To be called after the InputLogic has gotten a chance to act on the suggested words by the
     * IME for the full gesture, possibly updating the TextView to reflect the first suggestion.
     * <p>
     * This method must be run on the UI Thread.
     * @param suggestedWords suggested words by the IME for the full gesture.
     */
    public void onTailBatchInputResultShown(final SuggestedWords suggestedWords) {
        mGestureConsumer.onImeSuggestionsProcessed(suggestedWords,
                mInputLogic.getComposingStart(), mInputLogic.getComposingLength(),
                mDictionaryFacilitator);
    }

    // This method must run on the UI Thread.
    private void showGesturePreviewAndSetSuggestions(@NonNull final SuggestedWords suggestedWords,
                                              final boolean dismissGestureFloatingPreviewText) {
        setSuggestions(suggestedWords);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        mainKeyboardView.showGestureFloatingPreviewText(suggestedWords,
                dismissGestureFloatingPreviewText /* dismissDelayed */);
    }

    public boolean hasSuggestionStripView() {
        return null != mSuggestionStripView;
    }

    public void refreshCloudFallbackBadges() {
        if (hasSuggestionStripView()) {
            mSuggestionStripView.refreshCloudFallbackBadges();
        }
    }

    private void setSuggestedWords(final SuggestedWords suggestedWords) {
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        mInputLogic.setSuggestedWords(suggestedWords);
        // TODO: Modify this when we support suggestions with hard keyboard
        if (!hasSuggestionStripView()) {
            return;
        }
        if (!onEvaluateInputViewShown()) {
            return;
        }

        final boolean isEmptyApplicationSpecifiedCompletions =
                currentSettingsValues.isApplicationSpecifiedCompletionsOn()
                        && suggestedWords.isEmpty();
        final boolean noSuggestionsFromDictionaries = suggestedWords.isEmpty()
                || suggestedWords.isPunctuationSuggestions()
                || isEmptyApplicationSpecifiedCompletions;

        if (currentSettingsValues.isSuggestionsEnabledPerUserSettings()
                || currentSettingsValues.isApplicationSpecifiedCompletionsOn()
                // We should clear the contextual strip if there is no suggestion from dictionaries.
                || noSuggestionsFromDictionaries) {
            mSuggestionStripView.setSuggestions(suggestedWords,
                    mRichImm.getCurrentSubtype().isRtlSubtype());
            // Auto hide the toolbar if dictionary suggestions are available
            if (currentSettingsValues.mAutoHideToolbar && !noSuggestionsFromDictionaries) {
                mSuggestionStripView.setToolbarVisibility(false);
            }
        }
    }

    @Override
    public void setSuggestions(final SuggestedWords suggestedWords) {
        if (suggestedWords.isEmpty()) {
            // avoids showing clipboard suggestion when starting gesture typing
            // should be fine, as there will be another suggestion in a few ms
            // (but not a great style to avoid this visual glitch, maybe revert this commit and replace with sth better)
            if (suggestedWords.mInputStyle != SuggestedWords.INPUT_STYLE_UPDATE_BATCH)
                setNeutralSuggestionStrip();
        } else {
            setSuggestedWords(suggestedWords);
        }
        // Cache the auto-correction in accessibility code so we can speak it if the user
        // touches a key that will insert it.
        AccessibilityUtils.Companion.getInstance().setAutoCorrection(suggestedWords);
    }

    @Override
    public void showSuggestionStrip() {
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setToolbarVisibility(false);
        }
    }

    // Called from {@link SuggestionStripView} through the {@link SuggestionStripView#Listener}
    // interface
    @Override
    public void pickSuggestionManually(final SuggestedWordInfo suggestionInfo) {
        final InputTransaction completeInputTransaction = mInputLogic.onPickSuggestionManually(
                mSettings.getCurrent(), suggestionInfo,
                mKeyboardSwitcher.getKeyboardShiftMode(),
                mKeyboardSwitcher.getCurrentKeyboardScript(),
                mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
    }

    /**
     *  Checks if a recent clipboard suggestion is available. If available, it is set in suggestion strip.
     *  returns whether a clipboard suggestion has been set.
     */
    public boolean tryShowClipboardSuggestion() {
        final View clipboardView = mClipboardHistoryManager.getClipboardSuggestionView(getCurrentInputEditorInfo(), mSuggestionStripView);
        if (clipboardView != null && hasSuggestionStripView()) {
            mSuggestionStripView.setExternalSuggestionView(clipboardView, false);
            return true;
        }
        return false;
    }

    // This will first try showing a clipboard suggestion. On success, the toolbar will be hidden
    // if the "Auto hide toolbar" is enabled. Otherwise, an empty suggestion strip (if prediction
    // is enabled) or punctuation suggestions (if it's disabled) will be set.
    // Then, the toolbar will be shown automatically if the relevant setting is enabled
    // and there is a selection of text or it's the start of a line.
    @Override
    public void setNeutralSuggestionStrip() {
        final SettingsValues currentSettings = mSettings.getCurrent();
        if (tryShowClipboardSuggestion()) {
            // clipboard suggestion has been set
            if (hasSuggestionStripView() && currentSettings.mAutoHideToolbar)
                mSuggestionStripView.setToolbarVisibility(false);
            return;
        }
        final SuggestedWords neutralSuggestions = currentSettings.mSuggestPunctuation
                ? currentSettings.mSpacingAndPunctuations.mSuggestPuncList
                : SuggestedWords.getEmptyInstance();
        setSuggestedWords(neutralSuggestions);
        if (hasSuggestionStripView() && currentSettings.mAutoShowToolbar) {
            final int codePointBeforeCursor = mInputLogic.mConnection.getCodePointBeforeCursor();
            if (mInputLogic.mConnection.hasSelection()
                    || codePointBeforeCursor == Constants.NOT_A_CODE
                    || codePointBeforeCursor == Constants.CODE_ENTER) {
                mSuggestionStripView.setToolbarVisibility(true);
            }
        }
    }

    @Override
    public void removeSuggestion(final String word) {
        mDictionaryFacilitator.removeWord(word);
    }

    @Override
    public void removeExternalSuggestions() {
        setNeutralSuggestionStrip();
        mHandler.postResumeSuggestions(false);
    }

    private void loadKeyboard() {
        // Since we are switching languages, the most urgent thing is to let the keyboard graphics
        // update. LoadKeyboard does that, but we need to wait for buffer flip for it to be on
        // the screen. Anything we do right now will delay this, so wait until the next frame
        // before we do the rest, like reopening dictionaries and updating suggestions. So we
        // post a message.
        mHandler.postReopenDictionaries();
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            // Reload keyboard because the current language has been changed.
            mKeyboardSwitcher.reloadMainKeyboard();
        }
    }

    /**
     * After an input transaction has been executed, some state must be updated. This includes
     * the shift state of the keyboard and suggestions. This method looks at the finished
     * inputTransaction to find out what is necessary and updates the state accordingly.
     * @param inputTransaction The transaction that has been executed.
     */
    private void updateStateAfterInputTransaction(final InputTransaction inputTransaction) {
        switch (inputTransaction.getRequiredShiftUpdate()) {
            case InputTransaction.SHIFT_UPDATE_LATER -> mHandler.postUpdateShiftState();
            case InputTransaction.SHIFT_UPDATE_NOW -> mKeyboardSwitcher
                    .requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
            default -> {
            } // SHIFT_NO_UPDATE
        }
        if (inputTransaction.requiresUpdateSuggestions()) {
            final int inputStyle;
            if (inputTransaction.getEvent().isSuggestionStripPress()) {
                // Suggestion strip press: no input.
                inputStyle = SuggestedWords.INPUT_STYLE_NONE;
            } else if (inputTransaction.getEvent().isGesture()) {
                inputStyle = SuggestedWords.INPUT_STYLE_TAIL_BATCH;
            } else {
                inputStyle = SuggestedWords.INPUT_STYLE_TYPING;
            }
            mHandler.postUpdateSuggestionStrip(inputStyle);
        }
        if (inputTransaction.didAffectContents()) {
            mSubtypeState.setCurrentSubtypeHasBeenUsed();
        }
    }

    public void hapticAndAudioFeedback(final int code, final int repeatCount,
                                       final HapticEvent hapticEvent) {
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            // No need to feedback while finger is dragging.
            return;
        }
        if (repeatCount > 0) {
            // No need to feedback when repeat delete/cursor keys will have no effect.
            switch (code) {
            case KeyCode.DELETE, KeyCode.ARROW_LEFT, KeyCode.ARROW_UP, KeyCode.WORD_LEFT, KeyCode.PAGE_UP:
                if (!mInputLogic.mConnection.canDeleteCharacters())
                    return;
                break;
            case KeyCode.ARROW_RIGHT, KeyCode.ARROW_DOWN, KeyCode.WORD_RIGHT, KeyCode.PAGE_DOWN:
                if (!mInputLogic.mConnection.hasTextAfterCursor())
                    return;
                break;
            }
            // TODO: Use event time that the last feedback has been generated instead of relying on
            // a repeat count to thin out feedback.
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return;
            }
        }
        final AudioAndHapticFeedbackManager feedbackManager =
                AudioAndHapticFeedbackManager.getInstance();
        if (repeatCount == 0) {
            // TODO: Reconsider how to perform haptic feedback when repeating key.
            feedbackManager.performHapticFeedback(keyboardView, hapticEvent);
        }
        feedbackManager.performAudioFeedback(code, hapticEvent);
    }

    // Hooks for hardware keyboard
    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (mKeyboardActionListener.onKeyDown(keyCode, keyEvent))
            return true;
        return super.onKeyDown(keyCode, keyEvent);
    }

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent keyEvent) {
        if (mKeyboardActionListener.onKeyUp(keyCode, keyEvent))
            return true;
        return super.onKeyUp(keyCode, keyEvent);
    }

    // onKeyDown and onKeyUp are the main events we are interested in. There are two more events
    // related to handling of hardware key events that we may want to implement in the future:
    // boolean onKeyLongPress(final int keyCode, final KeyEvent event);
    // boolean onKeyMultiple(final int keyCode, final int count, final KeyEvent event);

    // receive ringer mode change.
    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                boolean dnd;
                try {
                    dnd = android.provider.Settings.Global.getInt(context.getContentResolver(), "zen_mode") != 0;
                } catch (android.provider.Settings.SettingNotFoundException e) {
                    dnd = false;
                    Log.w(TAG, "zen_mode setting not found, assuming disabled");
                }
                Log.i(TAG, "ringer mode changed, zen_mode on: "+dnd);
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged(dnd);
            }
        }
    };

    public ClipboardHistoryManager getClipboardHistoryManager() {
        return mClipboardHistoryManager;
    }

    void launchSettings() {
        mInputLogic.commitTyped(mSettings.getCurrent(), LastComposedWord.NOT_A_SEPARATOR);
        requestHideSelf(0);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        final Intent intent = new Intent();
        intent.setClass(LatinIME.this, SettingsActivity2.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    public void launchEmojiSearch() {
        Log.d("emoji-search", "before activity launch");
        startActivity(new Intent().setClass(this, EmojiSearchActivity.class)
                          .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION.equals(intent.getAction()) && ! isEmojiSearch()) {
            if (intent.getBooleanExtra(EmojiSearchActivity.IME_CLOSED_KEY, false)) {
                requestHideSelf(0);
            } else {
                mHandler.postDelayed(() -> KeyboardSwitcher.getInstance().setEmojiKeyboard(), 100);
                if (intent.hasExtra(EmojiSearchActivity.EMOJI_KEY)) {
                     onTextInput(intent.getStringExtra(EmojiSearchActivity.EMOJI_KEY));
                }
            }

            stopSelf(startId); // Allow the service to be destroyed when unbound
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    public boolean isEmojiSearch() {
        return getEmojiSearchActivityHeight() > 0;
    }

    private int getEmojiSearchActivityHeight() {
        return EmojiSearchActivity.Companion.decodePrivateImeOptions(getCurrentInputEditorInfo()).height();
    }

    public void dumpDictionaryForDebug(final String dictName) {
        if (!mDictionaryFacilitator.isActive()) {
            resetDictionaryFacilitatorIfNecessary();
        }
        mDictionaryFacilitator.dumpDictionaryForDebug(dictName);
    }

    public void debugDumpStateAndCrashWithException(final String context) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        String s = settingsValues.toString() + "\nAttributes : " + settingsValues.mInputAttributes +
                "\nContext : " + context;
        throw new RuntimeException(s);
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  VersionCode = " + BuildConfig.VERSION_CODE);
        p.println("  VersionName = " + BuildConfig.VERSION_NAME);
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
        final SettingsValues settingsValues = mSettings.getCurrent();
        p.println(settingsValues.dump());
        p.println(mDictionaryFacilitator.dump(this));
    }

    // slightly modified from Simple Keyboard: https://github.com/rkkr/simple-keyboard/blob/master/app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java
    @SuppressWarnings("deprecation")
    private void setNavigationBarColor() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (!settingsValues.mCustomNavBarColor)
            return;
        final int color = settingsValues.mColors.get(ColorType.NAVIGATION_BAR);
        final Window window = getWindow().getWindow();
        if (window == null)
            return;
        mOriginalNavBarColor = window.getNavigationBarColor();
        window.setNavigationBarColor(color);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        final View view = window.getDecorView();
        mOriginalNavBarFlags = view.getSystemUiVisibility();
        if (ColorUtilKt.isBrightColor(color)) {
            view.setSystemUiVisibility(mOriginalNavBarFlags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            view.setSystemUiVisibility(mOriginalNavBarFlags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @SuppressWarnings("deprecation")
    private void clearNavigationBarColor() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (!settingsValues.mCustomNavBarColor)
            return;
        final Window window = getWindow().getWindow();
        if (window == null) {
            return;
        }
        window.setNavigationBarColor(mOriginalNavBarColor);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        final View view = window.getDecorView();
        view.setSystemUiVisibility(mOriginalNavBarFlags);
    }

    // On HUAWEI devices with Android 12: a white bar may appear in landscape mode (issue #231)
    // We therefore need to make the color of the status bar transparent
    private void workaroundForHuaweiStatusBarIssue() {
        final Window window = getWindow().getWindow();
        if (window == null) {
            return;
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && Build.MANUFACTURER.equals("HUAWEI")) {
            window.setStatusBarColor(Color.TRANSPARENT);
        }
    }

    @SuppressLint("SwitchIntDef")
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        switch (level) {
            case TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL, TRIM_MEMORY_COMPLETE -> {
                KeyboardLayoutSet.onSystemLocaleChanged(); // clears caches, nothing else
                mKeyboardSwitcher.trimMemory();
            }
            // deallocateMemory always called on hiding, and should not be called when showing
        }
    }
}
