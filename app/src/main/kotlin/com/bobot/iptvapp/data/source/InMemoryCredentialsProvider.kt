package com.bobot.iptvapp.data.source

import com.bobot.iptvapp.domain.model.XtreamCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [CredentialsProvider] retained as a convenient test double.
 *
 * This class is **not** bound in the production Hilt graph since Task 9.
 * [com.bobot.iptvapp.data.preferences.DataStoreCredentialsProvider] is the production
 * binding (see [com.bobot.iptvapp.di.RepositoryModule]).
 *
 * Useful for:
 *  - Unit tests that need a controllable [CredentialsProvider] without DataStore I/O.
 *  - Integration tests targeting [RemoteXtreamSource] without a real DataStore.
 *
 * Credentials are `null` by default. Call [setCredentials] to configure at runtime.
 * This class is thread-safe: [_credentialsFlow] is a [MutableStateFlow] whose value
 * assignments are atomic on the JVM.
 */
@Singleton
class InMemoryCredentialsProvider @Inject constructor() : CredentialsProvider {

    private val _credentialsFlow = MutableStateFlow<XtreamCredentials?>(null)

    override suspend fun getCredentials(): XtreamCredentials? = _credentialsFlow.value

    override fun observeCredentials(): Flow<XtreamCredentials?> = _credentialsFlow.asStateFlow()

    override suspend fun setCredentials(credentials: XtreamCredentials) {
        _credentialsFlow.value = credentials
    }

    override suspend fun clearCredentials() {
        _credentialsFlow.value = null
    }
}
