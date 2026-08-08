package com.bobot.iptvapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Lets vertical D-pad presses move the focus *out* of a text field instead of dying inside it.
 *
 * ## Why this is needed
 * Compose Foundation's `BasicTextField` — which every Material 3 `OutlinedTextField` wraps —
 * installs its own key handler that maps [Key.DirectionUp]/[Key.DirectionDown] to the
 * "move caret one line up/down" editing commands. On a `singleLine = true` field there is no
 * other line to move to, so the command is a no-op — but the event is still **consumed**, and
 * never reaches the focus system. On a touch device nobody notices; with a remote control the
 * focus is simply stuck: no amount of `DPAD_DOWN` will ever leave the field.
 *
 * Observed on an Android TV emulator on 2026-08-08: on the search screen the focus could not
 * leave the query field to reach the filter chips or the results, and on the settings screen it
 * could not leave the server-URL field to reach the username, password, or save button — making
 * both screens unusable with a remote.
 *
 * ## How it works
 * [onPreviewKeyEvent] runs *before* the field's own handler, so the vertical keys are claimed
 * first and translated into an explicit [androidx.compose.ui.focus.FocusManager.moveFocus] call.
 *
 * The event is only consumed when the focus actually moved. When it did not — the field is the
 * last focusable in its direction — `false` is returned and the field handles the key as before,
 * so nothing is swallowed silently.
 *
 * Horizontal keys are deliberately left alone: on a single-line field they move the caret through
 * the text, which is what a user editing a value expects. Vertical keys carry no editing meaning
 * there, which is exactly why they are safe to repurpose for navigation.
 *
 * This is inert on touch-only devices, which never deliver D-pad key events, so it can be applied
 * unconditionally rather than gated behind [com.bobot.iptvapp.ui.util.rememberIsTvDevice].
 *
 * ## Usage
 * ```kotlin
 * OutlinedTextField(
 *     // …
 *     modifier = Modifier.fillMaxWidth().dpadFocusEscape(),
 * )
 * ```
 */
@Composable
internal fun Modifier.dpadFocusEscape(): Modifier {
    val focusManager = LocalFocusManager.current

    return onPreviewKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

        val direction = when (keyEvent.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> return@onPreviewKeyEvent false
        }

        focusManager.moveFocus(direction)
    }
}
