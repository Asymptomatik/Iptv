package com.bobot.iptvapp.domain.usecase

import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.LanguageFilterState
import com.bobot.iptvapp.domain.util.languageTag
import javax.inject.Inject

/**
 * Shared domain logic for the language filter feature, extracted from the near-identical
 * implementations previously duplicated in `HomeViewModel` (per-tab filter) and
 * `SearchViewModel` (global filter) — both built on top of
 * [com.bobot.iptvapp.domain.util.CategoryLanguage]/[com.bobot.iptvapp.domain.util.languageTag]
 * but re-implemented the same "derive distinct tags" / "does this category match the
 * selection" rules independently. This use-case centralises both operations so future
 * screens (and the two existing ones, once wired in a later task) share one, unit-tested,
 * source of truth.
 *
 * Framework-free and stateless on purpose (same rationale as
 * [com.bobot.iptvapp.domain.util.CategoryLanguage]): trivially unit-testable on the JVM, no
 * Android runtime dependency. `@Inject constructor` with no dependencies is enough for Hilt
 * to provide it automatically (see project convention in `domain.repository` implementations)
 * — no dedicated `@Provides` module needed.
 *
 * ## Semantics preserved from the pre-extraction call sites
 * - [availableLanguages]: distinct, non-null [Category.languageTag] values, in first-appearance
 *   order — mirrors both `HomeViewModel.buildRowsFlow`'s
 *   `categoriesResource.data.mapNotNull { it.languageTag() }.distinct()` (per catalog tab) and
 *   `SearchViewModel.SearchFilterContext.availableLanguages`'s equivalent computed over the
 *   union of all three content types' categories. The caller decides the scope (one tab's
 *   categories, or a concatenation of several) by choosing what [List] of [Category] to pass.
 * - [matches]: `selectedLanguage == null` ("Toutes") always matches; otherwise [category] must
 *   be non-null *and* its [Category.languageTag] must equal [selectedLanguage] exactly — a
 *   `null` category (unresolved, e.g. Search resolving a result item's `categoryId` that isn't
 *   in the currently-known category list) or one with no detectable tag never matches a
 *   non-null [selectedLanguage]. Mirrors both `HomeViewModel`'s inline
 *   `category.languageTag() == selectedLanguage` predicate (applied to non-null categories only,
 *   before grouping) and what used to be `SearchViewModel`'s own inline `matchesLanguage`, both
 *   now removed in favor of this shared use-case (Task 4).
 * - [filterCategories]: convenience helper over [matches] for callers (like `HomeViewModel`)
 *   that filter a `List<Category>` directly rather than resolving a nullable category per item.
 */
class FilterCatalogByLanguageUseCase @Inject constructor() {

    /**
     * Distinct, non-null [Category.languageTag] values found in [categories], in the order they
     * first appear. Callers decide the scope: pass a single tab's categories for a per-tab list
     * (Home), or a concatenation of several content types' categories for a union (Search).
     */
    fun availableLanguages(categories: List<Category>): List<String> =
        categories.mapNotNull { it.languageTag() }.distinct()

    /**
     * `true` when [category] passes the [selectedLanguage] filter.
     *
     * `selectedLanguage == null` ("Toutes") always matches, regardless of [category]. Otherwise,
     * [category] must be non-null and its [Category.languageTag] must equal [selectedLanguage]
     * exactly — a `null` category or one with no detectable tag is excluded as soon as a precise
     * filter is active.
     */
    fun matches(category: Category?, selectedLanguage: String?): Boolean =
        selectedLanguage == null || category?.languageTag() == selectedLanguage

    /**
     * Filters [categories], keeping only those for which [matches] returns `true` against
     * [selectedLanguage]. Convenience wrapper for callers that filter categories directly
     * (e.g. before grouping items by category), rather than resolving a nullable category per
     * item and calling [matches] individually.
     */
    fun filterCategories(categories: List<Category>, selectedLanguage: String?): List<Category> =
        categories.filter { category -> matches(category, selectedLanguage) }

    /**
     * Returns [state] with [LanguageFilterState.available] recomputed from [categories] via
     * [availableLanguages], leaving [LanguageFilterState.selected] untouched.
     *
     * Convenience for the "recompute available languages whenever a tab/screen's categories
     * change" step both `HomeViewModel` (per catalog tab) and `SearchViewModel` (union of all
     * three content types' categories) perform once wired onto [LanguageFilterState] (task 3/4).
     * Deliberately does not clear or validate [LanguageFilterState.selected] against the new
     * [available] list — mirrors the pre-existing behaviour in both call sites, where an active
     * selection is never reset just because it stops being present in the recomputed list.
     */
    fun deriveAvailableLanguages(categories: List<Category>, state: LanguageFilterState): LanguageFilterState =
        state.copy(available = availableLanguages(categories))
}
