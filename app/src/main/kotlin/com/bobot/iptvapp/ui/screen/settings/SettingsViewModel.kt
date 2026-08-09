package com.bobot.iptvapp.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.source.CatalogException
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state consumed by [SettingsScreen].
 *
 * @property serverUrl          Current text of the "server URL" field. Pre-filled from the
 *                               persisted credentials when the screen loads (see
 *                               [SettingsViewModel] init block).
 * @property username           Current text of the "username" field. Pre-filled like [serverUrl].
 * @property password           Current text of the "password" field. **Never** pre-filled — see
 *                               [SettingsViewModel] class KDoc for the rationale. An empty value
 *                               here means "keep the current password" when [onSaveCredentials]
 *                               is called.
 * @property isPasswordVisible  Whether [password] is rendered as plain text (`true`) or masked
 *                               (`false`, the default) — mirrors [com.bobot.iptvapp.ui.screen.onboarding.OnboardingUiState].
 * @property isLoading          `true` while [SettingsViewModel.onSaveCredentials] is awaiting the
 *                               real Xtream authentication call — disables the form fields and
 *                               action buttons so the user cannot trigger overlapping requests.
 * @property errorMessage       Human-readable error from the last failed action (save or, in
 *                               principle, any future action), or `null` when there is nothing to
 *                               show. Cleared whenever a field changes or another action starts.
 * @property infoMessage        Human-readable confirmation from the last successful action (save
 *                               or catalog reload), or `null` when there is nothing to show.
 *                               Cleared the same way as [errorMessage].
 * @property isLoggedOut        One-shot success signal. Becomes `true` exactly once
 *                               [SettingsViewModel.onLogout] finishes clearing the persisted
 *                               credentials; [SettingsScreen] observes this to trigger navigation
 *                               back to the Onboarding route with a fully cleared back stack.
 * @property isWifiOnlyDownloads Whether downloads are restricted to Wi-Fi networks — mirrors
 *                               [com.bobot.iptvapp.data.preferences.AppPreferencesStore.observeWifiOnlyDownloads],
 *                               collected in the [SettingsViewModel] init block and toggled via
 *                               [SettingsViewModel.onToggleWifiOnlyDownloads]. Defaults to `false`
 *                               (downloads allowed on any network) to match the preference store's
 *                               own default before the first emission arrives.
 */
data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isLoggedOut: Boolean = false,
    val isWifiOnlyDownloads: Boolean = false,
    val isLogoutConfirmationVisible: Boolean = false,
)

/**
 * Hilt ViewModel driving [SettingsScreen] (Task 15) — follows the `@HiltViewModel` +
 * `@Inject constructor` convention established by
 * [com.bobot.iptvapp.ui.screen.player.PlayerViewModel] (Task 13) and
 * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModel] (Task 14): only
 * `domain.repository` / `data.source` collaborators are injected, and a single
 * `StateFlow<SettingsUiState>` exposes everything [SettingsScreen] needs to render.
 *
 * ## Why the password field is never pre-filled
 * Unlike [serverUrl][SettingsUiState.serverUrl] and [username][SettingsUiState.username], the
 * password field always starts empty (with a "leave blank to keep the current password"
 * placeholder shown by [SettingsScreen]). Pre-filling a password field back into a plain-text
 * editable [androidx.compose.material3.OutlinedTextField] would mean holding the plaintext
 * password in Compose UI state for no functional benefit (the user did not ask to change it),
 * and would make "did the user intend to change the password, or just leave the pre-filled value
 * untouched?" ambiguous. An empty field with a clear placeholder is simpler and safer: blank on
 * submit unambiguously means "keep what is already stored" (see [onSaveCredentials]).
 *
 * ## Why a failed save restores the previous credentials instead of clearing them
 * This is the key behavioural difference from [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModel.onSubmit]
 * called out by this task. During onboarding there are no working credentials yet, so rolling
 * back a failed attempt via [CredentialsProvider.clearCredentials] is safe — there is nothing to
 * lose. Here, the user already has a **working** session before opening this screen. If they
 * edit the URL/username/password and the new combination is rejected by the server,
 * [CredentialsProvider.clearCredentials] would destroy the still-valid previous configuration and
 * silently sign the user out — a surprising and destructive side effect of a *failed* edit.
 * Instead, [onSaveCredentials] captures the last known-good [XtreamCredentials] before writing
 * the tentative new value, and on failure writes that captured value back via
 * [CredentialsProvider.setCredentials], leaving the app fully usable with the old (working)
 * configuration while the form stays filled in (untouched) so the user can correct and retry.
 *
 * @param catalogRepository    Used to call the real Xtream `authenticate()` endpoint (Task 8) and
 *                             to invalidate catalog caches (Task 8/9).
 * @param credentialsProvider  Used to read, persist, and clear credentials (Task 9).
 * @param appPreferencesStore  Used to read and persist the Wi-Fi-only downloads preference.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val credentialsProvider: CredentialsProvider,
    private val appPreferencesStore: AppPreferencesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * The last credentials known to work: the ones read from [credentialsProvider] when this
     * ViewModel was created, refreshed to the new value after every *successful*
     * [onSaveCredentials] call. Used both to fill in a blank password field on save (see
     * [onSaveCredentials]) and to restore a working configuration if a save attempt fails (see
     * class KDoc).
     */
    private var lastKnownGoodCredentials: XtreamCredentials? = null

    init {
        viewModelScope.launch {
            val credentials = credentialsProvider.getCredentials()
            lastKnownGoodCredentials = credentials
            if (credentials != null) {
                _uiState.update {
                    it.copy(serverUrl = credentials.baseUrl, username = credentials.username)
                }
            }
        }

        viewModelScope.launch {
            appPreferencesStore.observeWifiOnlyDownloads().collect { enabled ->
                _uiState.update { it.copy(isWifiOnlyDownloads = enabled) }
            }
        }
    }

    /** Updates the server URL field and clears any previously shown message. */
    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, errorMessage = null, infoMessage = null) }
    }

    /** Updates the username field and clears any previously shown message. */
    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null, infoMessage = null) }
    }

    /** Updates the password field and clears any previously shown message. */
    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null, infoMessage = null) }
    }

    /** Toggles [SettingsUiState.isPasswordVisible] — wired to the field's show/hide control. */
    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    /**
     * Validates the form, then persists and authenticates the entered credentials against the
     * real Xtream Codes server, reusing the persist-then-validate sequencing established by
     * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModel.onSubmit]. See this class's
     * KDoc for why the failure path restores the previous credentials instead of clearing them.
     * No-ops while a request is already in flight ([SettingsUiState.isLoading]).
     */
    fun onSaveCredentials() {
        val current = _uiState.value
        if (current.isLoading) return

        val baseUrl = current.serverUrl.trim()
        val username = current.username.trim()
        val typedPassword = current.password

        if (baseUrl.isBlank() || username.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Veuillez renseigner l'URL du serveur et l'identifiant.")
            }
            return
        }

        // Blank password field means "keep the current password" — fall back to the last
        // known-good value. If there is none (defensive: Settings is only normally reachable
        // once credentials already exist), a password must be typed explicitly.
        val effectivePassword = typedPassword.ifBlank { lastKnownGoodCredentials?.password.orEmpty() }
        if (effectivePassword.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Veuillez renseigner un mot de passe.")
            }
            return
        }

        val previousCredentials = lastKnownGoodCredentials
        val newCredentials = XtreamCredentials(baseUrl = baseUrl, username = username, password = effectivePassword)

        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }

        viewModelScope.launch {
            credentialsProvider.setCredentials(newCredentials)

            when (val result = catalogRepository.authenticate()) {
                is Resource.Success -> {
                    lastKnownGoodCredentials = newCredentials
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            password = "",
                            infoMessage = "Identifiants mis à jour avec succès.",
                        )
                    }
                }

                is Resource.Error -> {
                    // Restore the previous working configuration rather than clearing it — see
                    // class KDoc "Why a failed save restores the previous credentials instead of
                    // clearing them".
                    if (previousCredentials != null) {
                        credentialsProvider.setCredentials(previousCredentials)
                    } else {
                        // Defensive fallback only: reachable if this screen is somehow opened
                        // with no prior stored credentials at all, in which case there is
                        // nothing valid to restore.
                        credentialsProvider.clearCredentials()
                    }
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
     * Invalidates only the movies in-memory catalog cache via
     * [CatalogRepository.invalidateCache] and shows a movies-specific confirmation message. The
     * next Home/Detail/Search collection for [ContentType.MOVIE] re-fetches fresh content from
     * the server; live channels and series caches are left untouched. See [reloadCatalog] for the
     * shared mechanics.
     */
    fun onReloadMovies() = reloadCatalog(ContentType.MOVIE, "Films rechargés.")

    /**
     * Invalidates only the series in-memory catalog cache — same mechanics as [onReloadMovies],
     * scoped to [ContentType.SERIES].
     */
    fun onReloadSeries() = reloadCatalog(ContentType.SERIES, "Séries rechargées.")

    /**
     * Invalidates only the live channels in-memory catalog cache — same mechanics as
     * [onReloadMovies], scoped to [ContentType.LIVE].
     */
    fun onReloadChannels() = reloadCatalog(ContentType.LIVE, "Chaînes rechargées.")

    /**
     * Shared implementation behind [onReloadMovies], [onReloadSeries] and [onReloadChannels]:
     * invalidates the single-[type] cache and shows a type-specific confirmation message,
     * replacing the previous single global "Catalogue rechargé." message with one distinct per
     * content type.
     *
     * Both halves of the cache have to go, and in this order. [CatalogRepository.invalidateCache]
     * clears the in-memory session cache synchronously, which is what stops the current session
     * from answering the next read out of memory. But since schema v4 the repository also serves
     * reads from Room whenever the slice is fresh, so clearing memory alone would send the next
     * read straight to Room and the button would appear to do nothing at all — hence
     * [CatalogRepository.invalidatePersistentCache], which is `suspend` and therefore needs the
     * [viewModelScope] launch.
     *
     * The confirmation is shown immediately rather than after the marker clear completes: it
     * acknowledges the request, and the Room write is best-effort with nothing to report.
     */
    private fun reloadCatalog(type: ContentType, confirmationMessage: String) {
        catalogRepository.invalidateCache(type)
        viewModelScope.launch { catalogRepository.invalidatePersistentCache(type) }
        _uiState.update {
            it.copy(errorMessage = null, infoMessage = confirmationMessage)
        }
    }

    /**
     * Persists the Wi-Fi-only downloads preference via [appPreferencesStore]. The
     * [SettingsUiState.isWifiOnlyDownloads] field is not updated optimistically here — it is
     * driven solely by the [appPreferencesStore.observeWifiOnlyDownloads] collector started in
     * [init], so the toggle always reflects the actually-persisted value.
     */
    fun onToggleWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch {
            appPreferencesStore.setWifiOnlyDownloads(enabled)
        }
    }

    /**
     * Clears the persisted credentials and flips [SettingsUiState.isLoggedOut] once the clear
     * completes. [SettingsScreen] observes this one-shot signal to navigate back to the
     * Onboarding route with a fully cleared back stack, so the next app launch starts a clean
     * onboarding flow.
     */
    fun onLogout() {
        _uiState.update { it.copy(isLogoutConfirmationVisible = false) }
        viewModelScope.launch {
            credentialsProvider.clearCredentials()
            _uiState.update { it.copy(isLoggedOut = true) }
        }
    }

    /**
     * Arms the logout confirmation (QA finding M2: "Déconnexion" used to clear the credentials on
     * a single press). Nothing is cleared until [onLogout] is called.
     */
    fun onLogoutRequested() {
        _uiState.update { it.copy(isLogoutConfirmationVisible = true) }
    }

    /** Dismisses the logout confirmation without touching the persisted credentials. */
    fun onLogoutConfirmationDismissed() {
        _uiState.update { it.copy(isLogoutConfirmationVisible = false) }
    }

    /**
     * Maps a failed [Resource.Error] to a clear, actionable French message — same mapping as
     * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModel.errorMessageFor].
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
