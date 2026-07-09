package com.bobot.iptvapp.domain.model

/**
 * Immutable snapshot of a single language-filter widget's state: the language tags currently
 * selectable, and the one (if any) currently applied.
 *
 * Extracted so a screen with multiple *independent* language-filter widgets — each with its own
 * "available" list that grows as categories load, fed back by a `combine` that also reads its own
 * "selected" value — does not need to hand-roll the anti-feedback-loop pattern that pairing
 * requires once per widget.
 *
 * ## One instance per language filter, not one per screen
 * A [LanguageFilterState] is self-contained: [available] and [selected] both belong to the same
 * filter scope. [com.bobot.iptvapp.ui.screen.home.HomeViewModel] holds three independent instances
 * — one per Chaines/Films/Series tab, each with its own available languages and its own selection
 * (see that class's KDoc "Per-tab language filter") — because each tab's `combine` recomputes
 * [available] from that tab's own categories and writes it back into the same [MutableStateFlow]
 * the `combine` also reads [selected] from, which is exactly the shape this model was built for.
 *
 * [com.bobot.iptvapp.ui.screen.search.SearchViewModel] does **not** use this model: its single
 * global selector only ever *reads* the current selection (never writes [available] back into the
 * same state), so it keeps a plain `MutableStateFlow<String?>` plus a stateless derivation instead —
 * see that class's KDoc "Global language filter" for the full reasoning.
 *
 * @property available Distinct language tags currently selectable, in first-appearance order
 *                     (see [com.bobot.iptvapp.domain.usecase.FilterCatalogByLanguageUseCase.availableLanguages]).
 *                     Empty by default — the natural initial state before any catalog data has
 *                     loaded.
 * @property selected  The currently applied language tag, or `null` for "Toutes" (no filter).
 *                     `null` by default, matching `HomeViewModel`'s pre-existing per-tab default.
 */
data class LanguageFilterState(
    val available: List<String> = emptyList(),
    val selected: String? = null,
) {

    /**
     * Returns a copy with [selected] changed to [language], leaving [available] untouched.
     *
     * Named helper for the one-field update `HomeViewModel.onLanguageSelected` performs per tab —
     * equivalent to `copy(selected = language)` but reads as intent at the call site.
     */
    fun withSelection(language: String?): LanguageFilterState = copy(selected = language)
}
