package com.bobot.iptvapp.domain.model

/**
 * Runtime credentials required to connect to an Xtream Codes server.
 *
 * These three fields are the minimum required by every Xtream Codes API call:
 *  - [baseUrl] is passed to [com.bobot.iptvapp.data.remote.XtreamApiFactory] to create a typed
 *    Retrofit client bound to that server.
 *  - [username] and [password] are forwarded as query parameters on every API request.
 *
 * ## Persistence
 * Credentials are persisted via DataStore Preferences by
 * [com.bobot.iptvapp.data.preferences.DataStoreCredentialsProvider], written during
 * the onboarding flow (Task 14) and read at startup by
 * [com.bobot.iptvapp.data.source.RemoteXtreamSource] via [com.bobot.iptvapp.data.source.CredentialsProvider].
 *
 * @property baseUrl  Root URL of the Xtream server, e.g. `"http://example.com:8080"`.
 *                    Normalisation (trailing-slash handling) is performed by
 *                    [com.bobot.iptvapp.data.remote.XtreamApiFactory.create].
 * @property username Xtream Codes account username.
 * @property password Xtream Codes account password.
 */
data class XtreamCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
)
