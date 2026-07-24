package com.bobot.iptvapp.domain.usecase

import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.util.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Shared, pure data-loading algorithm extracted from the near-identical private helpers
 * previously duplicated in `HomeViewModel.loadCategoryScopedItems` and
 * `SearchViewModel.loadCategoryScopedItems` — both fetch a content type's items **one category
 * at a time** instead of a single unfiltered `categoryId = null` call, to bound the peak memory
 * footprint of one in-flight HTTP response + parsed payload (the OOM fix documented on both
 * ViewModels' class KDoc, "Category-scoped, on-demand loading (OOM fix)").
 *
 * This use-case's signature is the superset of the two near-identical private helpers: generic
 * over the item type `<T>` (`Channel` / `Movie` / `Series`), with an optional
 * [onCategoriesResolved] callback (defaulting to a no-op) so a caller that doesn't need the
 * resolved category list up front (Home's per-tab usage) can omit it, while a caller that does
 * (Search's global category/language derivation) can still observe it exactly once.
 *
 * ## "No sharing of UI helpers/cards/layout between screens" still applies — to the UI
 * The codebase convention documented on `SearchResultItem`'s KDoc ("Deliberately not
 * `HomeCardItem`") — that Home and Search never share UI-layer helpers, cards, or layout code —
 * remains valid and unaffected by this extraction. It exists to keep each screen's presentation
 * concerns independently evolvable. That rationale does not apply here: this use-case has zero
 * UI coupling, no `Composable`, no screen-specific model — it is a pure, framework-free
 * data-loading algorithm operating only on [Category], [Resource], and a caller-supplied
 * [MutableStateFlow]. Sharing a pure algorithm with no UI surface is exactly what the
 * `domain.usecase` package (see [FilterCatalogByLanguageUseCase] for a prior example of the
 * same pattern) exists for, and does not reintroduce the coupling the UI convention guards
 * against.
 *
 * ## Behaviour
 * Awaits [categoriesFlow]'s terminal (non-[Resource.Loading]) value first. On
 * [Resource.Error], forwarded to [itemsState] as-is, no per-category fetch attempted
 * (categories are cheap/small, so a failure here is a real, worth-surfacing problem). Otherwise
 * (categories [Resource.Success]), [onCategoriesResolved] is invoked exactly once with the
 * resolved list, [itemsState] is immediately set to `Resource.Success(emptyList())` — correctly
 * resolving the zero-categories edge case without entering the per-category loop — then each
 * category's items are fetched in turn via [fetchCategoryItems] (a plain suspend `for` loop,
 * never `async`/`combine`), merging into a running accumulator and re-publishing the accumulated
 * list to [itemsState] after every category so callers keep refining progressively as loading
 * continues.
 *
 * A single category's [fetchCategoryItems] call returning [Resource.Error] is treated as "no
 * items for that category" (silently skipped, loop continues) rather than aborting the whole
 * load — a category-level fetch failure should not discard items already accumulated from
 * other categories, nor prevent subsequent categories from being attempted.
 *
 * [categoriesFlow] is collected via a single `.first { ... }` call — callers are responsible for
 * not re-subscribing a cold, I/O-triggering `Flow` more than once per load.
 *
 * @param categoriesFlow Cold or hot [Flow] of the content type's categories; only its terminal
 *   (non-[Resource.Loading]) value is read.
 * @param itemsState Caller-owned [MutableStateFlow] mutated progressively as each category's
 *   items resolve. Never read by this function, only written.
 * @param onCategoriesResolved Invoked exactly once with the resolved category list, right where
 *   the [Resource.Success] branch already has [Resource.Success.data] in hand. Defaults to a
 *   no-op for callers that don't need it.
 * @param fetchCategoryItems Fetches one category's items by id. Expected to itself await its own
 *   terminal (non-[Resource.Loading]) value before returning.
 */
class LoadCategoryScopedCatalogUseCase @Inject constructor() {

    suspend operator fun <T> invoke(
        categoriesFlow: Flow<Resource<List<Category>>>,
        itemsState: MutableStateFlow<Resource<List<T>>>,
        onCategoriesResolved: (categories: List<Category>) -> Unit = {},
        fetchCategoryItems: suspend (categoryId: String) -> Resource<List<T>>,
    ) {
        when (val categoriesResource = categoriesFlow.first { it !is Resource.Loading }) {
            is Resource.Error ->
                itemsState.value = Resource.Error(categoriesResource.throwable, categoriesResource.message)
            is Resource.Success -> {
                onCategoriesResolved(categoriesResource.data)
                val accumulated = mutableListOf<T>()
                itemsState.value = Resource.Success(accumulated.toList())
                for (category in categoriesResource.data) {
                    val itemsResource = fetchCategoryItems(category.id)
                    if (itemsResource is Resource.Success) {
                        accumulated += itemsResource.data
                    }
                    itemsState.value = Resource.Success(accumulated.toList())
                }
            }
            Resource.Loading -> Unit // Unreachable: `first` predicate excludes Loading.
        }
    }
}
