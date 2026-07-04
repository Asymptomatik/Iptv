package com.bobot.iptvapp.data.source

/**
 * Domain-level exceptions thrown by [CatalogDataSource] implementations.
 *
 * Repositories (Task 8) should catch these in `runCatching` blocks and map them to
 * UI-presentable error states. Transport-level exceptions (IO, network) are not wrapped
 * here — they propagate as-is and should be caught at the repository boundary.
 */
sealed class CatalogException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * Thrown when the Xtream Codes server rejects the provided credentials.
     * Corresponds to `user_info.auth == 0` in the authenticate response.
     */
    class AuthenticationFailed(
        message: String = "Authentication failed: server rejected credentials.",
    ) : CatalogException(message)

    /**
     * Thrown by detail endpoints when the requested entity does not exist.
     * For example, [CatalogDataSource.getSeriesInfo] with an unknown series ID.
     *
     * @param entityId The ID that was requested and not found.
     */
    class NotFound(entityId: String) : CatalogException("Entity not found: $entityId")

    /**
     * Wraps a transport or parsing error with a human-readable message.
     * Used by the real network source ([RemoteXtreamSource]) to contextualise
     * underlying IO or JSON decoding failures.
     */
    class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : CatalogException(message, cause)
}
