package com.bobot.iptvapp.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Holds the one OpenSubtitles **consumer** API key of this application.
 *
 * OpenSubtitles requires one key per application and bans apps that make each user bring their
 * own. This app is personal and not distributed: its developer is its only user, so the key typed
 * in Réglages *is* the application's key — it is kept out of the source tree and the build instead
 * of being hardcoded, not collected from users.
 *
 * The key never flows back to the UI: [observeIsConfigured] is all a screen gets, so the field
 * that edits it can never be pre-filled. [getApiKey] is for the OpenSubtitles client alone.
 */
interface OpenSubtitlesApiKeyStore {

    /** Emits whether a key is stored, on collection then on every change. */
    fun observeIsConfigured(): Flow<Boolean>

    /** The stored key, or `null` when none is configured. */
    suspend fun getApiKey(): String?

    /** Stores [apiKey], trimmed. A blank value clears the key instead. */
    suspend fun setApiKey(apiKey: String)

    /** Forgets the stored key. */
    suspend fun clearApiKey()
}
