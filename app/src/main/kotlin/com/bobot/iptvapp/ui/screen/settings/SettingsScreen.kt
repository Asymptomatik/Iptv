package com.bobot.iptvapp.ui.screen.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobot.iptvapp.ui.components.ConfirmDialog
import com.bobot.iptvapp.ui.components.FocusableTextButton
import com.bobot.iptvapp.ui.components.GhostButton
import com.bobot.iptvapp.ui.components.GlassSurface
import com.bobot.iptvapp.ui.components.PrimaryButton
import com.bobot.iptvapp.ui.components.dpadFocusEscape
import com.bobot.iptvapp.ui.components.focusRingBehind
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.components.tvTextFieldEditGate
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.DisabledSurface
import com.bobot.iptvapp.ui.theme.GlassBorder
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextDimmed
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import com.bobot.iptvapp.ui.util.rememberIsTvDevice

/**
 * Settings screen (Task 15, reskinned Task 11) — "Cinematic Glass" V2.
 *
 * Glass sections: fields wrapped in glassSurface Box containers.
 * Actions: PrimaryButton for save, GhostButton for reload/manage/logout.
 * Nav row: FocusableTextButton for "Gérer les profils".
 * All settings logic (ViewModel, callbacks) unchanged.
 *
 * @param onNavigateToProfiles Invoked when "Gérer les profils" is clicked.
 * @param onLoggedOut          Invoked once, after logout clears persisted credentials.
 */
@Composable
fun SettingsScreen(
    onNavigateToProfiles: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isLoggedOut) {
        if (uiState.isLoggedOut) {
            onLoggedOut()
        }
    }

    SettingsContent(
        uiState = uiState,
        onServerUrlChange = viewModel::onServerUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onSaveCredentials = viewModel::onSaveCredentials,
        onReloadMovies = viewModel::onReloadMovies,
        onReloadSeries = viewModel::onReloadSeries,
        onReloadChannels = viewModel::onReloadChannels,
        onLogout = viewModel::onLogoutRequested,
        onConfirmLogout = viewModel::onLogout,
        onDismissLogoutConfirmation = viewModel::onLogoutConfirmationDismissed,
        onToggleWifiOnlyDownloads = viewModel::onToggleWifiOnlyDownloads,
        onNavigateToProfiles = onNavigateToProfiles,
        modifier = modifier,
    )
}

/**
 * The credential fields, as far as the Android TV browse/edit split needs to tell them apart
 * (QA finding Y3). At most one is being edited at a time, so the state is a nullable value of this
 * type rather than three booleans.
 */
private enum class SettingsField { SERVER_URL, USERNAME, PASSWORD }

/**
 * Stateless content — separated from [SettingsScreen] so it can be exercised directly in
 * `@Preview`s without a Hilt ViewModel.
 */
@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSaveCredentials: () -> Unit,
    onReloadMovies: () -> Unit,
    onReloadSeries: () -> Unit,
    onReloadChannels: () -> Unit,
    onLogout: () -> Unit,
    onConfirmLogout: () -> Unit,
    onDismissLogoutConfirmation: () -> Unit,
    onToggleWifiOnlyDownloads: (Boolean) -> Unit,
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // QA finding Y3 — on Android TV, every focused editable field asks for the on-screen keyboard,
    // which covers the bottom half of the display. The window hands its initial focus to the first
    // focusable node — the URL field — so merely opening this screen put the keyboard up, and
    // travelling down to "Enregistrer" needed a BACK between every field to get it out of the way.
    //
    // On TV the three fields therefore browse read-only and only start editing on DPAD_CENTER; see
    // [tvTextFieldEditGate]. Phones are untouched: there, focus means a tap, and the keyboard is
    // what the user asked for.
    val isTv = rememberIsTvDevice()
    var editingField by remember { mutableStateOf<SettingsField?>(null) }

    // QA finding M2 — "Déconnexion" used to clear the credentials on a single press. Only the
    // server credentials go; profiles, favorites, resume positions and downloads all survive.
    if (uiState.isLogoutConfirmationVisible) {
        ConfirmDialog(
            title = "Se déconnecter ?",
            message = "Les identifiants de ce serveur seront effacés et il faudra les saisir à " +
                "nouveau. Vos profils, favoris et téléchargements sont conservés.",
            confirmLabel = "Se déconnecter",
            onConfirm = onConfirmLogout,
            onDismiss = onDismissLogoutConfirmation,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase)
            .statusBarsPadding()
            .padding(Spacing.lg),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Paramètres",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            // ── Glass section: server credentials ──────────────────────────────
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                strong = false,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                ) {
                    Text(
                        text = "Serveur et identifiants",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Glass input: URL
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(shape = RoundedCornerShape(RadiusMd)),
                    ) {
                        OutlinedTextField(
                            value = uiState.serverUrl,
                            onValueChange = onServerUrlChange,
                            label = { Text("URL du serveur") },
                            placeholder = { Text("http://serveur.exemple.com:8080") },
                            singleLine = true,
                            enabled = !uiState.isLoading,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                            colors = settingsTextFieldColors(),
                            readOnly = isTv && editingField != SettingsField.SERVER_URL,
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusEscape()
                                .tvTextFieldEditGate(
                                    enabled = isTv,
                                    isEditing = editingField == SettingsField.SERVER_URL,
                                    onStartEditing = { editingField = SettingsField.SERVER_URL },
                                    onStopEditing = { editingField = null },
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Glass input: username
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(shape = RoundedCornerShape(RadiusMd)),
                    ) {
                        OutlinedTextField(
                            value = uiState.username,
                            onValueChange = onUsernameChange,
                            label = { Text("Nom d'utilisateur") },
                            singleLine = true,
                            enabled = !uiState.isLoading,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            colors = settingsTextFieldColors(),
                            readOnly = isTv && editingField != SettingsField.USERNAME,
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusEscape()
                                .tvTextFieldEditGate(
                                    enabled = isTv,
                                    isEditing = editingField == SettingsField.USERNAME,
                                    onStartEditing = { editingField = SettingsField.USERNAME },
                                    onStopEditing = { editingField = null },
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Glass input: password
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(shape = RoundedCornerShape(RadiusMd)),
                    ) {
                        OutlinedTextField(
                            value = uiState.password,
                            onValueChange = onPasswordChange,
                            label = { Text("Mot de passe") },
                            placeholder = { Text("Laisser vide pour ne pas changer") },
                            singleLine = true,
                            enabled = !uiState.isLoading,
                            visualTransformation = if (uiState.isPasswordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onSaveCredentials() }),
                            trailingIcon = {
                                TextButton(
                                    onClick = onTogglePasswordVisibility,
                                    enabled = !uiState.isLoading,
                                ) {
                                    Text(
                                        text = if (uiState.isPasswordVisible) "Masquer" else "Afficher",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                    )
                                }
                            },
                            colors = settingsTextFieldColors(),
                            readOnly = isTv && editingField != SettingsField.PASSWORD,
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusEscape()
                                .tvTextFieldEditGate(
                                    enabled = isTv,
                                    isEditing = editingField == SettingsField.PASSWORD,
                                    onStartEditing = { editingField = SettingsField.PASSWORD },
                                    onStopEditing = { editingField = null },
                                ),
                        )
                    }

                    if (uiState.errorMessage != null) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticError,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (uiState.infoMessage != null) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = uiState.infoMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // PrimaryButton for save action
                    PrimaryButton(
                        label = if (uiState.isLoading) "Enregistrement…" else "Enregistrer",
                        enabled = !uiState.isLoading,
                        onClick = onSaveCredentials,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            HorizontalDivider(color = GlassBorder)

            Spacer(modifier = Modifier.height(Spacing.lg))

            // ── Glass section: account actions ─────────────────────────────────
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                strong = false,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                ) {
                    // Nav action: Gérer les profils — FocusableTextButton (nav-style)
                    FocusableTextButton(
                        label = "Gérer les profils  ›",
                        onClick = onNavigateToProfiles,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // GhostButton for reload — one per catalog type
                    GhostButton(
                        label = "Recharger les films",
                        enabled = !uiState.isLoading,
                        onClick = onReloadMovies,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    GhostButton(
                        label = "Recharger les séries",
                        enabled = !uiState.isLoading,
                        onClick = onReloadSeries,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    GhostButton(
                        label = "Recharger les chaînes",
                        enabled = !uiState.isLoading,
                        onClick = onReloadChannels,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // GhostButton for logout (danger-tinted via label — no textColor param on GhostButton)
                    GhostButton(
                        label = "Déconnexion",
                        enabled = !uiState.isLoading,
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            HorizontalDivider(color = GlassBorder)

            Spacer(modifier = Modifier.height(Spacing.lg))

            // ── Glass section: downloads ────────────────────────────────────────
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                strong = false,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                ) {
                    Text(
                        text = "Téléchargements",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    WifiOnlyDownloadsRow(
                        checked = uiState.isWifiOnlyDownloads,
                        onCheckedChange = onToggleWifiOnlyDownloads,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xl))
        }
    }
}

/**
 * Row for the "Wi-Fi uniquement" downloads toggle inside the "Téléchargements" glass
 * section.
 *
 * The whole row (label + description + [Switch]) is wrapped in a single [Modifier.toggleable]
 * with `role = Role.Switch` and carries the shared [focusRingBehind] gradient ring so the
 * row — not just the small [Switch] hit target — responds to D-pad focus, consistent with
 * [FocusableTextButton] and the other focusable controls on this screen. The [Switch] itself
 * is purely decorative (`onCheckedChange = null`, no focus node of its own); the row's
 * `toggleable` modifier owns both the click/Enter handling and the D-pad focus.
 */
@Composable
private fun WifiOnlyDownloadsRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusRingAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "wifiOnlyToggleFocusRing",
    )

    val rowShape = RoundedCornerShape(RadiusMd)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .focusRingBehind(
                focusRingAlpha = focusRingAlpha,
                cornerRadiusDp = RadiusMd,
            )
            .glassSurface(shape = rowShape, strong = isFocused)
            .onFocusChanged { state -> isFocused = state.isFocused }
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Wi-Fi uniquement",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                text = "Limiter les téléchargements de contenu au réseau Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = AccentSolid,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = DisabledSurface,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/**
 * Glass-themed colours for the settings [OutlinedTextField]s — transparent container so
 * the glassSurface from the wrapping Box shows through, AccentSolid focus ring.
 */
@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    disabledTextColor = TextSecondary,
    focusedBorderColor = AccentSolid,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = DisabledSurface,
    focusedLabelColor = TextPrimary,
    unfocusedLabelColor = TextSecondary,
    disabledLabelColor = TextDimmed,
    cursorColor = AccentSolid,
    focusedPlaceholderColor = TextDimmed,
    unfocusedPlaceholderColor = TextDimmed,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
)

// ─── Preview ─────────────────────────────────────────────────────────────────

@Preview(name = "Settings — pre-filled", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun SettingsContentPreFilledPreview() {
    IptvAppTheme {
        SettingsContent(
            uiState = SettingsUiState(
                serverUrl = "http://example.com:8080",
                username = "user",
            ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSaveCredentials = {},
            onReloadMovies = {},
            onReloadSeries = {},
            onReloadChannels = {},
            onLogout = {},
            onConfirmLogout = {},
            onDismissLogoutConfirmation = {},
            onToggleWifiOnlyDownloads = {},
            onNavigateToProfiles = {},
        )
    }
}

@Preview(name = "Settings — error", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun SettingsContentErrorPreview() {
    IptvAppTheme {
        SettingsContent(
            uiState = SettingsUiState(
                serverUrl = "http://example.com:8080",
                username = "user",
                password = "wrong-password",
                errorMessage = "Identifiants incorrects. Vérifiez votre nom d'utilisateur et votre mot de passe.",
            ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSaveCredentials = {},
            onReloadMovies = {},
            onReloadSeries = {},
            onReloadChannels = {},
            onLogout = {},
            onConfirmLogout = {},
            onDismissLogoutConfirmation = {},
            onToggleWifiOnlyDownloads = {},
            onNavigateToProfiles = {},
        )
    }
}

@Preview(name = "Settings — info", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun SettingsContentInfoPreview() {
    IptvAppTheme {
        SettingsContent(
            uiState = SettingsUiState(
                serverUrl = "http://example.com:8080",
                username = "user",
                infoMessage = "Catalogue rechargé.",
            ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSaveCredentials = {},
            onReloadMovies = {},
            onReloadSeries = {},
            onReloadChannels = {},
            onLogout = {},
            onConfirmLogout = {},
            onDismissLogoutConfirmation = {},
            onToggleWifiOnlyDownloads = {},
            onNavigateToProfiles = {},
        )
    }
}
