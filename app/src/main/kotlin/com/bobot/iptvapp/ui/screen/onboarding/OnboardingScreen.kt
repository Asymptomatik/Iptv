package com.bobot.iptvapp.ui.screen.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobot.iptvapp.ui.components.PrimaryButton
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.theme.AccentGradient
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.DisabledSurface
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.RadiusLg
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextDimmed
import com.bobot.iptvapp.ui.theme.TextOnAccent
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary

/**
 * First-run onboarding screen (Task 14, reskinned Task 11): "Cinematic Glass" V2.
 *
 * App mark: AccentGradient rounded square icon at the top.
 * Form: glassSurface inputs with AccentSolid focus ring.
 * Submit: PrimaryButton (full width).
 * Error state: reskinned with SemanticError below fields.
 *
 * Validation/persist/nav logic unchanged from the ViewModel.
 *
 * @param onNavigateToProfiles Invoked once, after successful authentication + persistence.
 */
@Composable
fun OnboardingScreen(
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onNavigateToProfiles()
        }
    }

    OnboardingContent(
        uiState = uiState,
        onServerUrlChange = viewModel::onServerUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onSubmit = viewModel::onSubmit,
        modifier = modifier,
    )
}

/**
 * Stateless form content — separated from [OnboardingScreen] so it can be exercised directly
 * in `@Preview`s without a Hilt ViewModel.
 */
@Composable
private fun OnboardingContent(
    uiState: OnboardingUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase)
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App mark — AccentGradient rounded square
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(RadiusLg))
                    .background(brush = AccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "TV",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextOnAccent,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "Connexion à votre serveur IPTV",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "Renseignez les informations fournies par votre fournisseur Xtream Codes.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

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
                    colors = onboardingTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
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
                    colors = onboardingTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
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
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
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
                    colors = onboardingTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(Spacing.xl))

            // PrimaryButton full width submit
            PrimaryButton(
                label = if (uiState.isLoading) "Connexion en cours…" else "Se connecter",
                enabled = !uiState.isLoading,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Glass-themed colours for the onboarding [OutlinedTextField]s — transparent container so the
 * glassSurface from the wrapping Box shows through, AccentSolid focus ring.
 */
@Composable
private fun onboardingTextFieldColors() = OutlinedTextFieldDefaults.colors(
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

@Preview(name = "Onboarding — empty", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun OnboardingContentEmptyPreview() {
    IptvAppTheme {
        OnboardingContent(
            uiState = OnboardingUiState(),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSubmit = {},
        )
    }
}

@Preview(name = "Onboarding — error", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun OnboardingContentErrorPreview() {
    IptvAppTheme {
        OnboardingContent(
            uiState = OnboardingUiState(
                serverUrl = "http://example.com:8080",
                username = "user",
                password = "wrong-password",
                errorMessage = "Identifiants incorrects. Vérifiez votre nom d'utilisateur et votre mot de passe.",
            ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSubmit = {},
        )
    }
}

@Preview(name = "Onboarding — loading", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun OnboardingContentLoadingPreview() {
    IptvAppTheme {
        OnboardingContent(
            uiState = OnboardingUiState(
                serverUrl = "http://example.com:8080",
                username = "user",
                password = "secret",
                isLoading = true,
            ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSubmit = {},
        )
    }
}
