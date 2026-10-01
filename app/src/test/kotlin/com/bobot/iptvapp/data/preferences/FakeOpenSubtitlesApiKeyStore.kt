package com.bobot.iptvapp.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [OpenSubtitlesApiKeyStore] with the real store's trim / blank-clears contract. */
class FakeOpenSubtitlesApiKeyStore(initial: String? = null) : OpenSubtitlesApiKeyStore {

    val key = MutableStateFlow(initial)

    override fun observeIsConfigured(): Flow<Boolean> = key.map { it != null }

    override suspend fun getApiKey(): String? = key.value

    override suspend fun setApiKey(apiKey: String) {
        key.value = apiKey.trim().ifBlank { null }
    }

    override suspend fun clearApiKey() {
        key.value = null
    }
}
