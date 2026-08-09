package com.bobot.iptvapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusLg
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary

/**
 * Modal confirmation for a destructive action — QA finding M2: "Supprimer" (profile) and
 * "Déconnexion" (settings) both fired on a single press, with no way back.
 *
 * Deliberately built on [Dialog] + the app's own [GlassSurface] / [PrimaryButton] / [GhostButton]
 * rather than Material3's `AlertDialog`: the app-wide focus ring (see [FocusRingModifier]) that
 * makes the D-pad usable on Android TV lives in those components, and `AlertDialog`'s
 * `TextButton`s would drop it. The cancel action takes the *initial* focus so an accidental
 * DPAD_CENTER on TV — or a stray tap — dismisses instead of destroying.
 *
 * @param title       Short question, e.g. "Supprimer ce profil ?".
 * @param message     One or two lines spelling out the consequence, in concrete terms.
 * @param confirmLabel Verb of the destructive action, e.g. "Supprimer".
 * @param onConfirm   Invoked when the user confirms. The caller is responsible for closing the
 *                    dialog (this composable never assumes it stays mounted afterwards).
 * @param onDismiss   Invoked on cancel, back press, or outside tap.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Annuler",
) {
    val cancelFocusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // requestFocus() throws if the node is not attached yet on the first frame — the same
        // runCatching guard the detail screens use around their initial focus request.
        LaunchedEffect(Unit) {
            runCatching { cancelFocusRequester.requestFocus() }
        }

        GlassSurface(
            modifier = Modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(RadiusLg),
            strong = true,
        ) {
            Box(modifier = Modifier.background(BackgroundBase.copy(alpha = 0.92f))) {
                Column(modifier = Modifier.padding(Spacing.xl)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )

                    Spacer(modifier = Modifier.height(Spacing.xl))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.End),
                    ) {
                        GhostButton(
                            label = cancelLabel,
                            onClick = onDismiss,
                            modifier = Modifier.focusRequester(cancelFocusRequester),
                        )
                        PrimaryButton(
                            label = confirmLabel,
                            onClick = onConfirm,
                        )
                    }
                }
            }
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(name = "ConfirmDialog — delete profile", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun ConfirmDialogPreview() {
    IptvAppTheme {
        ConfirmDialog(
            title = "Supprimer ce profil ?",
            message = "Les favoris et les reprises de lecture de ce profil seront " +
                "définitivement perdus.",
            confirmLabel = "Supprimer",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
