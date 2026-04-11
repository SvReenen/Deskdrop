// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import helium314.keyboard.settings.screens.BrandHeader
import helium314.keyboard.settings.screens.brandTeal
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import helium314.keyboard.latin.LatinIME

/**
 * Lifecycle owner for ComposeView inside an IME dialog.
 * InputMethodService doesn't implement LifecycleOwner, so we provide one manually.
 */
private class DialogLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Button configuration for IME compose dialogs.
 * @param text Button label
 * @param action Callback receiving the AlertDialog (for dismiss control)
 */
data class DialogButton(val text: String, val action: (AlertDialog) -> Unit)

/** Active picker dialog, tracked to prevent stacking. */
private var activePickerDialog: AlertDialog? = null

/**
 * Shows an AlertDialog with Compose content, configured for IME windows.
 * The AlertDialog shell handles window token/type, ComposeView provides Material3 UI.
 *
 * @param ime The LatinIME service instance
 * @param title Dialog title (platform styled)
 * @param positiveButton Save/OK button config
 * @param negativeButton Cancel button config
 * @param neutralButton Optional third button config
 * @param preventAutoDismiss If true, positive/neutral buttons don't auto-dismiss
 * @param onDismiss Called when dialog is dismissed (for cleanup)
 * @param content Compose content for the dialog body
 * @return The created and shown AlertDialog
 */
fun showImeComposeDialog(
    ime: LatinIME,
    title: String = "",
    positiveButton: DialogButton? = null,
    negativeButton: DialogButton? = null,
    neutralButton: DialogButton? = null,
    preventAutoDismiss: Boolean = false,
    chromeless: Boolean = false,
    centerOnScreen: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit
): AlertDialog {
    // Dismiss any existing dialogs before showing a new one
    activePickerDialog?.let { if (it.isShowing) it.dismiss() }
    ime.activeDialog?.let { if (it.isShowing) it.dismiss() }

    val dialogContext = getPlatformDialogThemeContext(ime)

    val lifecycleOwner = DialogLifecycleOwner()
    lifecycleOwner.onCreate()

    val composeView = ComposeView(dialogContext).apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        setContent {
            Theme {
                content()
            }
        }
    }

    val builder = AlertDialog.Builder(dialogContext)
    if (!chromeless && title.isNotEmpty()) {
        builder.setTitle(title)
    }
    builder.setView(composeView)

    if (!chromeless) {
        if (preventAutoDismiss) {
            positiveButton?.let { builder.setPositiveButton(it.text, null) }
            negativeButton?.let { builder.setNegativeButton(it.text, null) }
            neutralButton?.let { builder.setNeutralButton(it.text, null) }
        } else {
            positiveButton?.let { btn ->
                builder.setPositiveButton(btn.text) { dialog, _ -> btn.action(dialog as AlertDialog) }
            }
            negativeButton?.let { btn ->
                builder.setNegativeButton(btn.text) { dialog, _ -> btn.action(dialog as AlertDialog) }
            }
            neutralButton?.let { btn ->
                builder.setNeutralButton(btn.text) { dialog, _ -> btn.action(dialog as AlertDialog) }
            }
        }
    }

    val dialog = builder.create()

    dialog.setOnDismissListener {
        lifecycleOwner.onDestroy()
        ime.setActiveDialog(null)
        onDismiss?.invoke()
    }

    ime.setActiveDialog(dialog)

    // IME window configuration: attach to keyboard window, keep keyboard visible
    val window = dialog.window
    val keyboardView = ime.keyboardSwitcher.mainKeyboardView
    if (window != null && keyboardView != null) {
        val lp = window.attributes
        lp.token = keyboardView.windowToken
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        lp.gravity = if (centerOnScreen) Gravity.CENTER else Gravity.TOP
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        window.attributes = lp
    }

    // Set lifecycle owners on the dialog's decor view BEFORE show(),
    // because Compose walks up the view tree from ComposeView to find them
    // during onAttachedToWindow which fires inside show()
    window?.decorView?.let { decorView ->
        decorView.setViewTreeLifecycleOwner(lifecycleOwner)
        decorView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        decorView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    dialog.show()
    lifecycleOwner.onResume()

    // Attach non-auto-dismiss click listeners after show()
    if (preventAutoDismiss && !chromeless) {
        positiveButton?.let { btn ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn.action(dialog) }
        }
        negativeButton?.let { btn ->
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { btn.action(dialog) }
        }
        neutralButton?.let { btn ->
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { btn.action(dialog) }
        }
    }

    return dialog
}

/**
 * Shows a secondary picker dialog (e.g. model picker) within an IME context.
 * Uses FLAG_ALT_FOCUSABLE_IM to allow interaction while parent dialog stays open.
 * Compose-styled with BrandHeader and RadioButtons.
 */
fun showImePickerDialog(
    ime: LatinIME,
    title: String,
    items: Array<String>,
    selectedIndex: Int = 0,
    onItemSelected: (Int) -> Unit
) {
    // Dismiss any existing picker before showing a new one
    activePickerDialog?.let { if (it.isShowing) it.dismiss() }

    val dialogContext = getPlatformDialogThemeContext(ime)

    val lifecycleOwner = DialogLifecycleOwner()
    lifecycleOwner.onCreate()

    val composeView = ComposeView(dialogContext).apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    val builder = AlertDialog.Builder(dialogContext)
    builder.setView(composeView)

    val dialog = builder.create()

    // Set content after dialog is created so we can reference it for dismiss
    composeView.setContent {
        Theme {
            PickerContent(title, items, selectedIndex) { which ->
                onItemSelected(which)
                dialog.dismiss()
            }
        }
    }

    dialog.setOnDismissListener {
        lifecycleOwner.onDestroy()
        if (activePickerDialog === dialog) activePickerDialog = null
    }

    activePickerDialog = dialog

    val window = dialog.window
    val keyboardView = ime.keyboardSwitcher.mainKeyboardView
    if (window != null && keyboardView != null) {
        val lp = window.attributes
        lp.token = keyboardView.windowToken
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    window?.decorView?.let { decorView ->
        decorView.setViewTreeLifecycleOwner(lifecycleOwner)
        decorView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        decorView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    dialog.show()
    lifecycleOwner.onResume()
}

@Composable
private fun PickerContent(
    title: String,
    items: Array<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BrandHeader(title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            items.forEachIndexed { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemSelected(index) }
                        .padding(horizontal = 8.dp)
                        .heightIn(min = 44.dp)
                ) {
                    RadioButton(
                        selected = index == selectedIndex,
                        onClick = { onItemSelected(index) },
                        colors = RadioButtonDefaults.colors(selectedColor = brandTeal()),
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}
