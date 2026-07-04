package com.bobot.iptvapp.ui.screen.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.domain.model.Profile
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The three visual states [ProfilesScreen] can render. See [ProfilesUiState.mode] for how the
 * ViewModel decides which one is active.
 */
enum class ProfilesMode {
    /** Grid of existing profile cards (+ an "add profile" card). Default mode. */
    SELECTION,

    /** Simple name form to create a new profile. */
    CREATE,

    /** Simple name form (pre-filled) to rename or delete an existing profile. */
    EDIT,
}

/**
 * UI state consumed by [ProfilesScreen].
 *
 * @property profiles           All existing profiles, as observed from [ProfileRepository.observeProfiles].
 * @property activeProfileId    The currently active profile id, as observed from
 *                                [AppPreferencesStore.observeActiveProfileId], or `null` if none is set.
 * @property mode                Which of [ProfilesMode]'s three screens to render. The flow collector
 *                                in [ProfilesViewModel]'s `init` block forces this to [ProfilesMode.CREATE]
 *                                whenever [profiles] is empty and the ViewModel is not already showing a
 *                                form — see class KDoc "Empty state" — so the caller never needs to
 *                                special-case an empty [profiles] list itself.
 * @property isManageModeActive  Whether the selection grid is in "manage" mode (Netflix-style "Gérer les
 *                                profils" toggle): while `true`, tapping a profile card opens [ProfilesMode.EDIT]
 *                                instead of selecting the profile and navigating to Home.
 * @property formName            Current text of the name field, shared by [ProfilesMode.CREATE] and
 *                                [ProfilesMode.EDIT].
 * @property editingProfileId    The id of the profile being edited, set only while [mode] is
 *                                [ProfilesMode.EDIT]; `null` in every other mode.
 * @property errorMessage        Human-readable validation/error message for the current form, or `null`.
 * @property navigateToHome      One-shot signal. Becomes `true` exactly once a profile has been selected
 *                                (or auto-selected right after creating the very first profile) and its id
 *                                has been persisted as active — [ProfilesScreen] observes this to trigger
 *                                navigation to the Home route, mirroring the `isAuthenticated` /
 *                                `isLoggedOut` one-shot pattern used by
 *                                [com.bobot.iptvapp.ui.screen.onboarding.OnboardingUiState] and
 *                                [com.bobot.iptvapp.ui.screen.settings.SettingsUiState].
 */
data class ProfilesUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val mode: ProfilesMode = ProfilesMode.SELECTION,
    val isManageModeActive: Boolean = false,
    val formName: String = "",
    val editingProfileId: String? = null,
    val errorMessage: String? = null,
    val navigateToHome: Boolean = false,
)

/**
 * Hilt ViewModel driving [ProfilesScreen] (Task 16) — follows the `@HiltViewModel` +
 * `@Inject constructor` convention established by
 * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModel] (Task 14) and
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModel] (Task 15): only
 * `domain.repository` / `data.preferences` collaborators are injected (never Room, Compose, or
 * Media3 types), and a single `StateFlow<ProfilesUiState>` exposes everything [ProfilesScreen]
 * needs to render.
 *
 * ## Empty state (first launch after onboarding)
 * [ProfileRepository.observeProfiles] can legitimately emit an empty list — this happens exactly
 * once, right after onboarding completes and before the user has ever created a profile (the app
 * is unusable without at least one profile). Rather than rendering an empty grid, the `init`
 * block's collector forces [ProfilesUiState.mode] to [ProfilesMode.CREATE] whenever it observes
 * an empty [ProfilesUiState.profiles] list while still in [ProfilesMode.SELECTION], so
 * [ProfilesScreen] always shows the profile-creation form directly in that case (brief: "the app
 * is unusable without at least one profile"). Because [onDeleteProfile] below refuses to delete
 * the last remaining profile, this forced-empty path is only ever reachable on a genuinely first
 * launch, never as a side effect of profile management.
 *
 * ## Selecting a profile
 * [onSelectProfile] persists the tapped profile's id via
 * [AppPreferencesStore.setActiveProfileId] (a suspend call) before flipping
 * [ProfilesUiState.navigateToHome] — mirroring the async-precondition-then-one-shot-flag pattern
 * used by [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModel.onSubmit]'s
 * `isAuthenticated` flag, since navigation here also depends on an asynchronous write completing
 * first.
 *
 * ## Auto-selecting the very first profile ever created
 * [onSubmitCreate] auto-selects (persists as active + navigates to Home) the profile it just
 * created **only** when [ProfilesUiState.profiles] was empty before the call — i.e. exactly the
 * forced first-run flow above, where creating a profile is the only way to leave onboarding.
 * Creating an *additional* profile from an already non-empty grid (the "Ajouter un profil" card)
 * instead returns to [ProfilesMode.SELECTION] without touching the active profile or navigating,
 * since the user already has a working active profile and simply added a sibling one.
 *
 * ## Deleting the active profile
 * [onDeleteProfile] refuses to delete the last remaining profile ([ProfilesUiState.errorMessage]
 * explains why) — the app always needs at least one profile to be usable. When the profile being
 * deleted is the currently active one and at least one other profile remains, its active-id
 * selection is cleared via [AppPreferencesStore.setActiveProfileId] (`null`), forcing the user
 * back through profile selection on this same screen rather than silently keeping a
 * now-nonexistent profile "active".
 *
 * ## Cleaning up on deletion
 * Neither [FavoritesRepository] nor [PlaybackProgressRepository] rows are cascade-deleted at the
 * database level when a profile is removed — [ProfileRepository.deleteProfile]'s KDoc explicitly
 * makes this the caller's responsibility. [onDeleteProfile] therefore also calls
 * [FavoritesRepository.clearFavorites] and [PlaybackProgressRepository.clearProgress] for the
 * deleted profile's id, alongside [ProfileRepository.deleteProfile], to avoid leaving orphaned
 * favorites/progress rows behind.
 *
 * @param profileRepository          Read/write access to profiles (Task 11): `observeProfiles`,
 *                                     `createProfile`, `updateProfile`, `deleteProfile`.
 * @param appPreferencesStore        Read/write access to which profile id is currently active
 *                                     (Task 9).
 * @param favoritesRepository        Used solely to purge a deleted profile's favorites — see
 *                                     "Cleaning up on deletion" above.
 * @param playbackProgressRepository Used solely to purge a deleted profile's playback progress —
 *                                     see "Cleaning up on deletion" above.
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val appPreferencesStore: AppPreferencesStore,
    private val favoritesRepository: FavoritesRepository,
    private val playbackProgressRepository: PlaybackProgressRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                profileRepository.observeProfiles(),
                appPreferencesStore.observeActiveProfileId(),
            ) { profiles, activeProfileId -> profiles to activeProfileId }
                .collect { (profiles, activeProfileId) ->
                    _uiState.update { current ->
                        current.copy(
                            profiles = profiles,
                            activeProfileId = activeProfileId,
                            // See class KDoc "Empty state": force the creation form the moment
                            // the list is (still) empty, but never fight the user out of a form
                            // they are already filling in.
                            mode = if (profiles.isEmpty() && current.mode == ProfilesMode.SELECTION) {
                                ProfilesMode.CREATE
                            } else {
                                current.mode
                            },
                        )
                    }
                }
        }
    }

    /** Toggles the selection grid's "Gérer les profils" manage mode. No-op outside [ProfilesMode.SELECTION]. */
    fun onToggleManageMode() {
        _uiState.update { it.copy(isManageModeActive = !it.isManageModeActive) }
    }

    /**
     * Card tap in [ProfilesMode.SELECTION]. Routes to editing or selecting depending on
     * [ProfilesUiState.isManageModeActive] — the "manage" toggle changes what tapping a card does,
     * matching the Netflix "Gérer les profils" convention.
     */
    fun onProfileCardClick(profile: Profile) {
        if (_uiState.value.isManageModeActive) {
            onEditProfileClick(profile)
        } else {
            onSelectProfile(profile)
        }
    }

    /**
     * Persists [profile]'s id as the active profile, then signals [ProfilesUiState.navigateToHome].
     * See class KDoc "Selecting a profile".
     */
    fun onSelectProfile(profile: Profile) {
        viewModelScope.launch {
            appPreferencesStore.setActiveProfileId(profile.id)
            _uiState.update { it.copy(activeProfileId = profile.id, navigateToHome = true) }
        }
    }

    /** Opens the blank creation form (the "Ajouter un profil" card in [ProfilesMode.SELECTION]). */
    fun onAddProfileClick() {
        _uiState.update {
            it.copy(
                mode = ProfilesMode.CREATE,
                formName = "",
                editingProfileId = null,
                errorMessage = null,
            )
        }
    }

    /** Opens the pre-filled edit form for [profile] (reached via [onProfileCardClick] in manage mode). */
    fun onEditProfileClick(profile: Profile) {
        _uiState.update {
            it.copy(
                mode = ProfilesMode.EDIT,
                formName = profile.name,
                editingProfileId = profile.id,
                errorMessage = null,
            )
        }
    }

    /**
     * Returns to [ProfilesMode.SELECTION] without saving. Only ever shown by [ProfilesScreen] when
     * [ProfilesUiState.profiles] is non-empty (see class/screen KDoc) — cancelling the forced
     * first-run creation form would have nowhere meaningful to return to.
     */
    fun onCancelForm() {
        _uiState.update {
            it.copy(
                mode = ProfilesMode.SELECTION,
                formName = "",
                editingProfileId = null,
                errorMessage = null,
            )
        }
    }

    /** Updates the shared name field and clears any previously shown error. */
    fun onFormNameChange(value: String) {
        _uiState.update { it.copy(formName = value, errorMessage = null) }
    }

    /**
     * Validates the name field, then creates a new profile via [ProfileRepository.createProfile].
     * See class KDoc "Auto-selecting the very first profile ever created" for the branching
     * behaviour on success.
     */
    fun onSubmitCreate() {
        val current = _uiState.value
        val name = current.formName.trim()

        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Veuillez saisir un nom de profil.") }
            return
        }

        val isFirstProfileEver = current.profiles.isEmpty()

        viewModelScope.launch {
            val created = profileRepository.createProfile(name = name)

            if (isFirstProfileEver) {
                appPreferencesStore.setActiveProfileId(created.id)
                _uiState.update {
                    it.copy(
                        mode = ProfilesMode.SELECTION,
                        formName = "",
                        activeProfileId = created.id,
                        navigateToHome = true,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(mode = ProfilesMode.SELECTION, formName = "")
                }
            }
        }
    }

    /**
     * Validates the name field, then persists the rename via [ProfileRepository.updateProfile].
     * No-ops (defensively) if [ProfilesUiState.editingProfileId] no longer matches a known profile.
     */
    fun onSubmitEdit() {
        val current = _uiState.value
        val name = current.formName.trim()

        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Veuillez saisir un nom de profil.") }
            return
        }

        val editingId = current.editingProfileId ?: return
        val existing = current.profiles.find { it.id == editingId } ?: return

        viewModelScope.launch {
            profileRepository.updateProfile(existing.copy(name = name))
            _uiState.update {
                it.copy(mode = ProfilesMode.SELECTION, formName = "", editingProfileId = null)
            }
        }
    }

    /**
     * Deletes the profile currently open in [ProfilesMode.EDIT]. See class KDoc "Deleting the
     * active profile" for the last-profile guard and active-id cleanup behaviour, and "Cleaning
     * up on deletion" for the favorites/playback-progress purge performed alongside it.
     */
    fun onDeleteProfile() {
        val current = _uiState.value
        val editingId = current.editingProfileId ?: return

        if (current.profiles.size <= 1) {
            _uiState.update {
                it.copy(errorMessage = "Impossible de supprimer le dernier profil restant.")
            }
            return
        }

        viewModelScope.launch {
            profileRepository.deleteProfile(editingId)
            favoritesRepository.clearFavorites(editingId)
            playbackProgressRepository.clearProgress(editingId)
            if (current.activeProfileId == editingId) {
                appPreferencesStore.setActiveProfileId(null)
            }
            _uiState.update {
                it.copy(
                    mode = ProfilesMode.SELECTION,
                    formName = "",
                    editingProfileId = null,
                    activeProfileId = if (it.activeProfileId == editingId) null else it.activeProfileId,
                )
            }
        }
    }
}
