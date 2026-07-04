package com.bobot.iptvapp.domain.util

/**
 * UI-layer state wrapper for async data operations.
 *
 * ## Flow vs suspend convention (from [com.bobot.iptvapp.domain.repository.CatalogRepository])
 * | Return type              | Used for                                                      |
 * |--------------------------|---------------------------------------------------------------|
 * | `Flow<Resource<List<T>>>`| Category lists and stream rows — reactive, session-cached    |
 * | `suspend Resource<T>`    | One-shot detail fetches — triggered on demand (e.g. navigation) |
 *
 * ## UI handling contract
 * Every consumer (ViewModel / Compose screen) must handle all three states:
 *  - [Loading] — show shimmer / skeleton placeholder
 *  - [Success] — render content; check `data.isEmpty()` for empty-state UI
 *  - [Error]   — show error card or Snackbar with a retry action
 */
sealed class Resource<out T> {

    /**
     * Indicates an in-progress data load.
     *
     * Emitted first by every `Flow<Resource<T>>` before the data source is queried.
     * Suspend methods do not emit Loading — callers manage their own loading state.
     */
    data object Loading : Resource<Nothing>()

    /**
     * The operation completed successfully with [data].
     *
     * [data] may be an empty list — the UI is responsible for distinguishing
     * "empty content" from an error state.
     */
    data class Success<T>(val data: T) : Resource<T>()

    /**
     * The operation failed.
     *
     * At least one of [throwable] or [message] is non-null. [message] defaults to
     * [Throwable.message] when not specified explicitly.
     *
     * Common [throwable] types from the catalog layer:
     *  - [com.bobot.iptvapp.data.source.CatalogException.AuthenticationFailed]
     *  - [com.bobot.iptvapp.data.source.CatalogException.NotFound]
     *  - [com.bobot.iptvapp.data.source.CatalogException.NetworkError]
     *
     * @param throwable The underlying exception; use its type to decide which error UI to show.
     * @param message   Human-readable description, suitable for display in a Snackbar or
     *                  error card without exposing stack-trace internals.
     */
    data class Error(
        val throwable: Throwable? = null,
        val message: String? = throwable?.message,
    ) : Resource<Nothing>()
}
