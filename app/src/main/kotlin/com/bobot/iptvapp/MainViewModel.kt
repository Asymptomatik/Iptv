package com.bobot.iptvapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.preferences.LogoutPurgeMarkerStore
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.logout.LogoutPurgeState
import com.bobot.iptvapp.navigation.AppRoute
import com.bobot.iptvapp.navigation.Onboarding
import com.bobot.iptvapp.navigation.Profiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    private val logoutPurgeMarkerStore: LogoutPurgeMarkerStore,
    private val logoutCoordinator: LogoutCoordinator,
) : ViewModel() {

    private val _startupState = MutableStateFlow<StartupState>(StartupState.Resolving)

    /** See [StartupState]; [StartupState.Resolving] until the one-shot check completes. */
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    /** Retries a recovery that failed — wired to the [StartupState.RecoveryFailed] screen. */
    fun onRetryRecovery() {
        resolve()
    }

    init {
        resolve()
        observeLogoutAfterBootstrap()
    }

    /**
     * Routes back to [Onboarding] on a logout that completes once bootstrap is already past
     * [StartupState.Resolving] — the case a screen-scoped observer cannot cover on its own because
     * the screen that asked for the logout may already be gone by the time the purge settles (see
     * [com.bobot.iptvapp.data.logout.LogoutCoordinator]'s class KDoc). [MainViewModel], unlike a
     * screen's ViewModel, is never recreated mid-session, so it is the one place guaranteed to still
     * be there to observe the coordinator's outcome and act on it.
     *
     * Gated on `!is Resolving` so it never races [resolve]'s own bootstrap-triggered recovery: while
     * still resolving, [resolve] alone decides the destination from that same [LogoutPurgeState].
     */
    private fun observeLogoutAfterBootstrap() {
        viewModelScope.launch {
            logoutCoordinator.state.collect { purge ->
                if (_startupState.value !is StartupState.Resolving && purge is LogoutPurgeState.Completed) {
                    _startupState.value = StartupState.Ready(Onboarding)
                }
            }
        }
    }

    /**
     * Finishes any owed purge *first*, then reads the state it left behind.
     *
     * The order is the whole point. Credentials are the purge's last step, so an install that died
     * mid-purge still has them at launch: resolving the destination from a plain credentials read
     * would open [Profiles] on the previous account, and the purge would then delete the catalogue
     * caches and the credentials from under the user, leaving every call failing authentication
     * with no route out until the next restart.
     */
    private fun resolve() {
        viewModelScope.launch {
            _startupState.value = StartupState.Resolving
            logoutCoordinator.recoverIfNeeded()
            _startupState.value = when {
                // A purge that is still owed means the local stores still hold the previous
                // account. There is no route that is safe to enter: an authenticated one shows
                // data that is on its way out, and onboarding would write a new account's
                // credentials in front of a purge that would then delete them too.
                logoutPurgeMarkerStore.isPurgePending() ->
                    StartupState.RecoveryFailed(RECOVERY_FAILED_MESSAGE)

                credentialsProvider.getCredentials() != null -> StartupState.Ready(Profiles)
                else -> StartupState.Ready(Onboarding)
            }
        }
    }

    private companion object {
        const val RECOVERY_FAILED_MESSAGE =
            "La déconnexion précédente n'a pas pu supprimer toutes les données locales de ce " +
                "compte. L'application reste bloquée ici tant qu'elles sont là. Réessayez ; si le " +
                "problème persiste, libérez de l'espace de stockage puis relancez l'application."
    }
}

/**
 * What [MainActivity] should be showing before, and instead of, the navigation graph.
 *
 * A third case exists because the logout purge can be owed at launch: entering an authenticated
 * route while it is being finished would drop the user into a session whose credentials are about
 * to be — or have already been — deleted underneath them.
 */
sealed interface StartupState {

    /** The one-shot check (and any owed purge) is still in flight. */
    data object Resolving : StartupState

    /** The graph can be composed on [destination]. */
    data class Ready(val destination: AppRoute) : StartupState

    /** A purge is still owed and could not be finished; [message] is shown with a retry. */
    data class RecoveryFailed(val message: String) : StartupState
}
