package com.bobot.iptvapp.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for application-level user preferences that are persisted across sessions.
 *
 * This interface manages lightweight scalar preferences stored in DataStore Preferences.
 * It intentionally excludes content entities (profiles, channels, favourites) which
 * belong in Room (Task 10 onwards).
 *
 * ## Active profile
 * The active profile ID is stored here as a plain [String?]. The corresponding
 * [com.bobot.iptvapp.domain.model.Profile] entity lives in Room (Task 10); this store
 * only tracks *which* profile ID is currently selected. Room is the source of truth for
 * profile data; DataStore is the source of truth for which profile is active.
 *
 * ## Extension points
 * Preferences such as preferred stream format (ts/m3u8) or last selected category ID
 * can be added to this interface and its implementation in future tasks without any
 * structural changes.
 *
 * @see DataStoreAppPreferencesStore
 */
interface AppPreferencesStore {

    /**
     * Emits the ID of the currently active profile on collection, then on every change.
     * Emits `null` when no profile is active (e.g. first run, or after profile deletion).
     */
    fun observeActiveProfileId(): Flow<String?>

    /**
     * Returns the ID of the currently active profile, or `null` if none has been set.
     */
    suspend fun getActiveProfileId(): String?

    /**
     * Sets the active profile to [id]. Pass `null` to clear the selection
     * (e.g. after the active profile is deleted in Task 10/11).
     */
    suspend fun setActiveProfileId(id: String?)

    /**
     * Emits the Wi-Fi only downloads setting on collection, then on every change.
     * When `true`, content downloads should only occur on Wi-Fi networks.
     * Default value is `false` (downloads allowed on any network).
     */
    fun observeWifiOnlyDownloads(): Flow<Boolean>

    /**
     * Sets the Wi-Fi only downloads preference to [enabled].
     */
    suspend fun setWifiOnlyDownloads(enabled: Boolean)

    /**
     * Emits the default language filter tag on collection, then on every change.
     *
     * Semantics:
     *  - the underlying key being absent (never written) yields `"FR"`, the product default;
     *  - an explicitly stored empty string yields `null`, meaning "All / no filter", which is
     *    therefore distinguishable from "never configured".
     *
     * No migration is required for this preference: an absent key already produces the
     * intended default ("FR"), so existing installs simply pick up "FR" on their next launch
     * without any explicit migration step.
     *
     * Note: as of this task, no production caller writes this preference yet. It is exposed
     * ahead of a future user-facing setting; it is not dead code.
     */
    fun observeDefaultLanguageFilter(): Flow<String?>

    /**
     * One-shot read of the default language filter tag. See [observeDefaultLanguageFilter]
     * for the absent-vs-empty semantics.
     */
    suspend fun getDefaultLanguageFilter(): String?

    /**
     * Sets the default language filter to [tag].
     *
     * Passing `null` stores an empty string, meaning "All / no filter". A non-null [tag] is
     * trimmed and upper-cased before being stored (e.g. `"  fr "` is stored as `"FR"`).
     */
    suspend fun setDefaultLanguageFilter(tag: String?)
}
