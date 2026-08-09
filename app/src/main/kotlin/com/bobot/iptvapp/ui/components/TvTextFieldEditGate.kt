package com.bobot.iptvapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Splits a text field's behaviour on Android TV into *browsing* and *editing*, so that moving the
 * D-pad across a form does not drag the on-screen keyboard along with it.
 *
 * ## Why this is needed
 * A focused, editable Compose text field asks for the IME. On a phone that is exactly right — the
 * user tapped the field, they want to type. On a TV the focus moves for a completely different
 * reason: the user is *travelling through* the form with the D-pad, and every field they cross
 * throws a full-screen keyboard over the bottom half of the display, which then has to be
 * dismissed with `BACK` before the next `DOWN` press does anything.
 *
 * Observed on an Android TV emulator on 2026-08-09 (QA finding Y3): opening Réglages popped the
 * keyboard immediately — the URL field is the first focusable node, so the window handed it the
 * initial focus — and reaching "Enregistrer" from there took a `BACK` between every single field.
 *
 * ## How it works
 * The field stays focusable but is held `readOnly` while browsing, which is what keeps the IME
 * away; `DPAD_CENTER`/`ENTER` on the focused field switches it to editing, and losing focus
 * switches it back. The caller owns that one piece of state because `readOnly` is a parameter of
 * the field, not something a modifier can reach:
 *
 * ```kotlin
 * OutlinedTextField(
 *     // …
 *     readOnly = isTv && editingField != SettingsField.USERNAME,
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .dpadFocusEscape()
 *         .tvTextFieldEditGate(
 *             enabled = isTv,
 *             isEditing = editingField == SettingsField.USERNAME,
 *             onStartEditing = { editingField = SettingsField.USERNAME },
 *             onStopEditing = { editingField = null },
 *         ),
 * )
 * ```
 *
 * `BACK` is deliberately not handled. While the IME is up it belongs to the keyboard window and
 * never reaches Compose, so a handler here would only fire on the *second* press — which would
 * make `BACK` look like it needs pressing twice to leave the screen. Dismissing the keyboard with
 * `BACK` and then moving the D-pad exits editing anyway, through [onFocusChanged].
 *
 * Gated on [enabled] rather than applied unconditionally, because `readOnly` on a phone would
 * break plain tap-to-type: there is no `DPAD_CENTER` to switch the field back on.
 */
@Composable
internal fun Modifier.tvTextFieldEditGate(
    enabled: Boolean,
    isEditing: Boolean,
    onStartEditing: () -> Unit,
    onStopEditing: () -> Unit,
): Modifier {
    if (!enabled) return this

    return this
        .onFocusChanged { focusState ->
            if (!focusState.isFocused && isEditing) onStopEditing()
        }
        .onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown || isEditing) return@onPreviewKeyEvent false

            when (keyEvent.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    onStartEditing()
                    true
                }
                else -> false
            }
        }
}
