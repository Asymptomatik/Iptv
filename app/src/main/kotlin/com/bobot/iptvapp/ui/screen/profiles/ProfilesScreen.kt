package com.bobot.iptvapp.ui.screen.profiles

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bobot.iptvapp.domain.model.Profile
import com.bobot.iptvapp.ui.components.GhostButton
import com.bobot.iptvapp.ui.components.PrimaryButton
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.theme.AccentBlue
import com.bobot.iptvapp.ui.theme.AccentCyan
import com.bobot.iptvapp.ui.theme.AccentGradient
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.AccentViolet
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.BackgroundElevated
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.DisabledSurface
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.LayoutDimens
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextDimmed
import com.bobot.iptvapp.ui.theme.TextOnAccent
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary

/**
 * Profile selection & management screen (Task 16, reskinned Task 11) — "Cinematic Glass" V2.
 *
 * Profile tiles: initial letter over AccentGradient variant backgrounds.
 * Focus: glow/scale animation (existing pattern).
 * "Ajouter" tile: glass surface with "+" in AccentGradient.
 * Forms: glass inputs, PrimaryButton/GhostButton actions.
 * LazyRowFocusPadding already applied correctly — kept as-is.
 *
 * @param onNavigateToHome Invoked once a profile has been selected.
 */
@Composable
fun ProfilesScreen(
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.navigateToHome) {
        if (uiState.navigateToHome) {
            onNavigateToHome()
        }
    }

    ProfilesContent(
        uiState = uiState,
        onToggleManageMode = viewModel::onToggleManageMode,
        onProfileCardClick = viewModel::onProfileCardClick,
        onAddProfileClick = viewModel::onAddProfileClick,
        onCancelForm = viewModel::onCancelForm,
        onFormNameChange = viewModel::onFormNameChange,
        onSubmitCreate = viewModel::onSubmitCreate,
        onSubmitEdit = viewModel::onSubmitEdit,
        onDeleteProfile = viewModel::onDeleteProfile,
        modifier = modifier,
    )
}

/**
 * Stateless content — separated from [ProfilesScreen] so it can be exercised directly in
 * `@Preview`s without a Hilt ViewModel.
 */
@Composable
private fun ProfilesContent(
    uiState: ProfilesUiState,
    onToggleManageMode: () -> Unit,
    onProfileCardClick: (Profile) -> Unit,
    onAddProfileClick: () -> Unit,
    onCancelForm: () -> Unit,
    onFormNameChange: (String) -> Unit,
    onSubmitCreate: () -> Unit,
    onSubmitEdit: () -> Unit,
    onDeleteProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase)
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        when (uiState.mode) {
            ProfilesMode.SELECTION -> ProfilesSelectionContent(
                uiState = uiState,
                onToggleManageMode = onToggleManageMode,
                onProfileCardClick = onProfileCardClick,
                onAddProfileClick = onAddProfileClick,
            )

            ProfilesMode.CREATE -> ProfilesFormContent(
                title = if (uiState.profiles.isEmpty()) {
                    "Bienvenue ! Créez votre premier profil"
                } else {
                    "Ajouter un profil"
                },
                formName = uiState.formName,
                errorMessage = uiState.errorMessage,
                showCancel = uiState.profiles.isNotEmpty(),
                showDelete = false,
                submitLabel = "Créer",
                onFormNameChange = onFormNameChange,
                onSubmit = onSubmitCreate,
                onCancel = onCancelForm,
                onDelete = onDeleteProfile,
            )

            ProfilesMode.EDIT -> ProfilesFormContent(
                title = "Modifier le profil",
                formName = uiState.formName,
                errorMessage = uiState.errorMessage,
                showCancel = true,
                showDelete = true,
                submitLabel = "Enregistrer",
                onFormNameChange = onFormNameChange,
                onSubmit = onSubmitEdit,
                onCancel = onCancelForm,
                onDelete = onDeleteProfile,
            )
        }
    }
}

// ─── Selection grid ────────────────────────────────────────────────────────────

@Composable
private fun ProfilesSelectionContent(
    uiState: ProfilesUiState,
    onToggleManageMode: () -> Unit,
    onProfileCardClick: (Profile) -> Unit,
    onAddProfileClick: () -> Unit,
) {
    // Initial D-pad focus target, mirroring HomeScreen's pattern. Without it nothing is focused
    // when this screen appears, and since it is the app's start destination for a returning user
    // (see AppNavGraph), the very first DPAD_CENTER press on a TV does nothing — the user has to
    // press a direction key first just to give focus to something. Targets the first profile, or
    // the "add profile" card when there is no profile yet.
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(uiState.profiles) {
        // requestFocus() throws when the target node is not attached yet (e.g. the LazyRow has not
        // composed its first item for this emission). Same defensive handling as HomeScreen.
        runCatching { initialFocusRequester.requestFocus() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Qui regarde ?",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(LayoutDimens.CardRowSpacing),
            contentPadding = PaddingValues(LayoutDimens.LazyRowFocusPadding),
        ) {
            itemsIndexed(uiState.profiles, key = { _, profile -> profile.id }) { index, profile ->
                val cardModifier = Modifier
                    .width(CardDimens.PosterWidthPhone)
                    .let { if (index == 0) it.focusRequester(initialFocusRequester) else it }

                ProfileCard(
                    profile = profile,
                    isManageModeActive = uiState.isManageModeActive,
                    onClick = { onProfileCardClick(profile) },
                    avatarGradient = avatarGradientForProfile(profile),
                    modifier = cardModifier,
                )
            }

            item {
                val addModifier = Modifier
                    .width(CardDimens.PosterWidthPhone)
                    .let {
                        if (uiState.profiles.isEmpty()) it.focusRequester(initialFocusRequester) else it
                    }

                AddProfileCard(
                    onClick = onAddProfileClick,
                    modifier = addModifier,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        if (uiState.profiles.isNotEmpty()) {
            ManageModeToggle(
                isManageModeActive = uiState.isManageModeActive,
                onClick = onToggleManageMode,
            )
        }
    }
}

/**
 * Returns an [AccentGradient]-family [Brush] for the avatar background, varied by
 * the profile's name hash so different profiles get visually distinct tiles.
 * Uses only the existing accent color tokens — no hardcoded hex.
 */
private fun avatarGradientForProfile(profile: Profile): Brush {
    val gradients = listOf(
        // Violet → Cyan (default AccentGradient direction)
        Brush.linearGradient(listOf(AccentViolet, AccentCyan)),
        // Cyan → Violet (reversed)
        Brush.linearGradient(listOf(AccentCyan, AccentViolet)),
        // Blue → Violet
        Brush.linearGradient(listOf(AccentBlue, AccentViolet)),
        // Violet → Blue
        Brush.linearGradient(listOf(AccentViolet, AccentBlue)),
    )
    val index = (profile.name.hashCode() and 0x7FFFFFFF) % gradients.size
    return gradients[index]
}

/**
 * Profile tile with AccentGradient-variant avatar background and initial letter.
 * Focus: scale + AccentSolid border (consistent with existing FocusableCard pattern).
 */
@Composable
private fun ProfileCard(
    profile: Profile,
    isManageModeActive: Boolean,
    onClick: () -> Unit,
    avatarGradient: Brush,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) CardDimens.FocusedCardScale else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "profileCardScale",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) CardDimens.FocusBorderWidth else 0.dp,
        animationSpec = tween(durationMillis = 150),
        label = "profileCardBorder",
    )

    val shape = MaterialTheme.shapes.medium

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .border(
                    width = borderWidth,
                    color = if (isFocused) AccentSolid else Color.Transparent,
                    shape = shape,
                )
                .onFocusChanged { focusState -> isFocused = focusState.isFocused }
                .clickable(onClick = onClick),
        ) {
            if (profile.avatarUrl != null) {
                val placeholderPainter = remember { ColorPainter(BackgroundElevated) }
                val errorPainter = remember { ColorPainter(BackgroundElevated) }
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = profile.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = placeholderPainter,
                    error = errorPainter,
                    fallback = placeholderPainter,
                )
            } else {
                // AccentGradient avatar background with initial letter
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush = avatarGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextOnAccent,
                    )
                }
            }

            if (isManageModeActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, BackgroundBase.copy(alpha = 0.9f)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Modifier",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        modifier = Modifier.padding(vertical = CardDimens.TitleVerticalPadding),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        Text(
            text = profile.name,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * "Ajouter un profil" glass tile — glassSurface with AccentGradient "+" glyph.
 */
@Composable
private fun AddProfileCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .glassSurface(shape = shape, strong = isFocused)
                .onFocusChanged { focusState -> isFocused = focusState.isFocused }
                .clickable(onClick = onClick),
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.headlineMedium,
                color = if (isFocused) AccentSolid else TextSecondary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        Text(
            text = "Ajouter un profil",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Gérer les profils" / "Terminer" toggle — glass surface, focus ring. */
@Composable
private fun ManageModeToggle(
    isManageModeActive: Boolean,
    onClick: () -> Unit,
) {
    GhostButton(
        label = if (isManageModeActive) "Terminer" else "Gérer les profils",
        onClick = onClick,
    )
}

// ─── Create / Edit form ─────────────────────────────────────────────────────────

@Composable
private fun ProfilesFormContent(
    title: String,
    formName: String,
    errorMessage: String?,
    showCancel: Boolean,
    showDelete: Boolean,
    submitLabel: String,
    onFormNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        // Glass input field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(RadiusMd)),
        ) {
            OutlinedTextField(
                value = formName,
                onValueChange = onFormNameChange,
                label = { Text("Nom du profil") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                colors = profilesTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticError,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        PrimaryButton(
            label = submitLabel,
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
        )

        if (showDelete) {
            Spacer(modifier = Modifier.height(Spacing.md))
            GhostButton(
                label = "Supprimer",
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showCancel) {
            Spacer(modifier = Modifier.height(Spacing.md))
            GhostButton(
                label = "Annuler",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Glass-themed colours for the profiles form [OutlinedTextField]s — transparent container so
 * the glassSurface from the wrapping Box shows through, AccentSolid focus ring.
 */
@Composable
private fun profilesTextFieldColors() = OutlinedTextFieldDefaults.colors(
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
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
)

// ─── Preview ─────────────────────────────────────────────────────────────────

@Preview(name = "Profiles — selection", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun ProfilesContentSelectionPreview() {
    IptvAppTheme {
        ProfilesContent(
            uiState = ProfilesUiState(
                profiles = listOf(
                    Profile(id = "1", name = "Alice", avatarUrl = null),
                    Profile(id = "2", name = "Kids", avatarUrl = null),
                ),
                activeProfileId = "1",
            ),
            onToggleManageMode = {},
            onProfileCardClick = {},
            onAddProfileClick = {},
            onCancelForm = {},
            onFormNameChange = {},
            onSubmitCreate = {},
            onSubmitEdit = {},
            onDeleteProfile = {},
        )
    }
}

@Preview(name = "Profiles — first run empty state", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun ProfilesContentEmptyPreview() {
    IptvAppTheme {
        ProfilesContent(
            uiState = ProfilesUiState(profiles = emptyList(), mode = ProfilesMode.CREATE),
            onToggleManageMode = {},
            onProfileCardClick = {},
            onAddProfileClick = {},
            onCancelForm = {},
            onFormNameChange = {},
            onSubmitCreate = {},
            onSubmitEdit = {},
            onDeleteProfile = {},
        )
    }
}

@Preview(name = "Profiles — edit", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun ProfilesContentEditPreview() {
    IptvAppTheme {
        ProfilesContent(
            uiState = ProfilesUiState(
                profiles = listOf(Profile(id = "1", name = "Alice", avatarUrl = null)),
                mode = ProfilesMode.EDIT,
                formName = "Alice",
                editingProfileId = "1",
            ),
            onToggleManageMode = {},
            onProfileCardClick = {},
            onAddProfileClick = {},
            onCancelForm = {},
            onFormNameChange = {},
            onSubmitCreate = {},
            onSubmitEdit = {},
            onDeleteProfile = {},
        )
    }
}
