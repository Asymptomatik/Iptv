package com.bobot.iptvapp.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.source.CatalogException
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state consumed by [OnboardingScreen].
 *
 * @property serverUrl          Current text of the "server URL" field, exactly as typed.
 * @property username           Current text of the "username" field, exactly as typed.
 * @property password           Current text of the "password" field, exactly as typed.
 * @property isPasswordVisible  Whether [password] is rendered as plain text (`true`) or
 *                               masked (`false`, the default).
 * @property isLoading          `true` while [OnboardingViewModel.onSubmit] is awaiting the
 *                               real Xtream authentication call — drives the submit button's
 *                               disabled/"connecting" state and disables the three fields so
 *                               the user cannot edit mid-request.
 * @property errorMessage       Human-readable error to display above the submit button, or
 *                               `null` when there is nothing to show. Cleared automatically
 *                               whenever any field changes, so a corrected re-submission does
 *                               not show a stale message.
 * @property isAuthenticated    One-shot success signal. Becomes `true` exactly once
 *                               authentication succeeds and credentials are persisted;
 *                               [OnboardingScreen] observes this to trigger navigation to the
 *                               Profiles route. Never reset to `false` afterwards — the screen
 *                               (and this ViewModel) leave composition immediately since the
 *                               Onboarding destination is popped off the back stack.
 */
data class OnboardingUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isAuthenticated: Boolean = false,
)

/**
 * Hilt ViewModel driving [OnboardingScreen] (Task 14) — follows the `@HiltViewModel` +
 * `@Inject constructor` convention established by [com.bobot.iptvapp.ui.screen.player.PlayerViewModel]
 * (Task 13, the first ViewModel in this codebase): only `domain.repository` / `data.source`
 * collaborators are injected (never Android Views, `NavHostController`, or Compose types),
 * and a single `StateFlow<OnboardingUiState>` exposes everything [OnboardingScreen] needs to
 * render. Navigation is signalled via [OnboardingUiState.isAuthenticated] rather than a lambda
 * parameter on this class, mirroring how [PlayerScreen][com.bobot.iptvapp.ui.screen.player.PlayerScreen]
 * keeps all `NavHostController` ownership inside `AppNavGraph`/the composable layer.
 *
 * ## Why credentials are persisted *before* calling `authenticate()`
 * [CatalogRepository.authenticate] takes no arguments — per its Task 8 contract, it always
 * validates whatever [CredentialsProvider.getCredentials] currently returns (see
 * [com.bobot.iptvapp.data.source.RemoteXtreamSource.resolveApi]). There is no overload that
 * accepts ad-hoc credentials to "dry-run" before persisting. To validate the credentials the
 * user just typed, [onSubmit] therefore:
 *  1. writes the form values to [credentialsProvider] via [CredentialsProvider.setCredentials]
 *     (tentative write);
 *  2. calls [catalogRepository.authenticate];
 *  3a. on success, leaves the just-written credentials persisted (this is the "persist on
 *      success" behaviour required by the brief) and flips [OnboardingUiState.isAuthenticated];
 *  3b. on failure, rolls back via [CredentialsProvider.clearCredentials] — so an invalid
 *      combination is never left sitting in DataStore — and surfaces a message from
 *      [errorMessageFor], leaving the form fully editable for a retry (no fields are cleared).
 *
 * @param catalogRepository   Used to call the real Xtream `authenticate()` endpoint (Task 8).
 * @param credentialsProvider Used to persist (or roll back) the entered credentials (Task 9).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val credentialsProvider: CredentialsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Updates the server URL field and clears any previously shown error. */
    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, errorMessage = null) }
    }

    /** Updates the username field and clears any previously shown error. */
    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
    }

    /** Updates the password field and clears any previously shown error. */
    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    /** Toggles [OnboardingUiState.isPasswordVisible] — wired to the field's show/hide control. */
    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    /**
     * Validates the form is complete, then persists and authenticates the entered credentials
     * against the real Xtream Codes server. See class KDoc for the persist-then-validate
     * sequencing rationale. No-ops while a request is already in flight
     * ([OnboardingUiState.isLoading]).
     */
    fun onSubmit() {
        val current = _uiState.value
        if (current.isLoading) return

        val baseUrl = current.serverUrl.trim()
        val username = current.username.trim()
        val password = current.password

        if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Veuillez renseigner l'URL du serveur, l'identifiant et le mot de passe.")
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            credentialsProvider.setCredentials(
                XtreamCredentials(baseUrl = baseUrl, username = username, password = password),
            )

            when (val result = catalogRepository.authenticate()) {
                is Resource.Success -> {
                    _uiState.update { it.copy(isLoading = false, isAuthenticated = true) }
                }

                is Resource.Error -> {
                    // Roll back the tentative write — an invalid combination must not remain
                    // persisted (see class KDoc).
                    credentialsProvider.clearCredentials()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = errorMessageFor(result),
                        )
                    }
                }

                Resource.Loading -> {
                    // authenticate() is a suspend call — per Resource's documented contract
                    // (domain.util.Resource KDoc: "Suspend methods do not emit Loading"), this
                    // branch is unreachable. Kept only to satisfy the sealed `when` exhaustiveness
                    // check.
                }
            }
        }
    }

    /**
     * Maps a failed [Resource.Error] to a clear, actionable French message, distinguishing the
     * [CatalogException] subtypes documented on [CatalogRepository.authenticate] and
     * [CatalogException] itself.
     */
    private fun errorMessageFor(error: Resource.Error): String = when (error.throwable) {
        is CatalogException.AuthenticationFailed ->
            "Identifiants incorrects. Vérifiez votre nom d'utilisateur et votre mot de passe."

        is CatalogException.NetworkError ->
            "Impossible de contacter le serveur. Vérifiez l'URL et votre connexion internet."

        is CatalogException.NotFound ->
            // Not expected from authenticate() today, but handled defensively since it is a
            // sibling CatalogException subtype.
            "Le serveur indiqué est introuvable. Vérifiez l'URL saisie."

        else ->
            error.message ?: "Une erreur inattendue est survenue. Veuillez réessayer."
    }
}
