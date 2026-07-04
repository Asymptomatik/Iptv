package com.bobot.iptvapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.navigation.AppRoute
import com.bobot.iptvapp.navigation.Onboarding
import com.bobot.iptvapp.navigation.Profiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Resolves [MainActivity]'s dynamic Navigation Compose start destination (Task 16, Volet B).
 *
 * ## The gap this closes
 * [com.bobot.iptvapp.navigation.AppNavGraph] previously hard-coded `startDestination = Onboarding`
 * unconditionally. Once Task 9 persisted Xtream Codes credentials across process restarts, that
 * hard-coding became a real UX bug: a returning user with valid, already-stored credentials would
 * be sent back through the onboarding form on every single app launch instead of straight to
 * profile selection.
 *
 * ## Why a ViewModel (and not a plain synchronous check)
 * [CredentialsProvider.getCredentials] is a `suspend` function, but `NavHost` needs a
 * `startDestination` value at first composition — synchronously. This ViewModel exposes a
 * nullable [startDestination] `StateFlow`: `null` means "not yet determined" (the one-shot
 * suspend check is still in flight), and [MainActivity] renders a minimal blank/loading surface
 * until it flips to a real, non-null [AppRoute]. This mirrors the existing one-shot-signal
 * convention used across this codebase's other Hilt ViewModels (e.g.
 * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingUiState.isAuthenticated],
 * [com.bobot.iptvapp.ui.screen.settings.SettingsUiState.isLoggedOut]) — a `StateFlow` field that
 * starts in a "not yet" state and is updated exactly once by a suspend call in `init`.
 *
 * ## Resolution rule
 * - [CredentialsProvider.getCredentials] returns non-`null` (credentials already persisted) →
 *   [Profiles]. Per the brief, a returning user always lands on profile selection, never
 *   straight on [com.bobot.iptvapp.navigation.Home] — multi-profile selection happens on every
 *   launch after the very first one.
 * - [CredentialsProvider.getCredentials] returns `null` (no credentials yet) → [Onboarding],
 *   exactly the previous hard-coded behaviour, preserved for first-run users.
 *
 * @param credentialsProvider Used to read whether Xtream Codes credentials are already
 *                             persisted (Task 9).
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val credentialsProvider: CredentialsProvider,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<AppRoute?>(null)

    /** `null` while the one-shot credentials check is still in flight; see class KDoc. */
    val startDestination: StateFlow<AppRoute?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            val hasCredentials = credentialsProvider.getCredentials() != null
            _startDestination.value = if (hasCredentials) Profiles else Onboarding
        }
    }
}
