package com.bobot.iptvapp.ui.screen.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.ui.components.CategoryChip
import com.bobot.iptvapp.ui.components.FocusableCard
import com.bobot.iptvapp.ui.components.FocusableTextButton
import com.bobot.iptvapp.ui.components.SectionTitle
import com.bobot.iptvapp.ui.components.dpadFocusEscape
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.BackgroundElevated
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.LayoutDimens
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextDimmed
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import com.bobot.iptvapp.ui.util.rememberIsTvDevice

/**
 * Search screen (Task 21, reskinned Task 11) — "Cinematic Glass" V2.
 *
 * Glass search field (glassSurface modifier + AccentSolid focus ring),
 * CategoryChip type filters (Tout / Chaînes / Films / Séries), results in
 * SectionTitle + LazyRow of FocusableCards. LazyRow uses vertical contentPadding
 * = LazyRowFocusPadding so focus lift/glow is not clipped (T7 review fix).
 *
 * Filter state is local UI state (no VM change) — it narrows the already-filtered
 * VM results client-side.
 *
 * @param onNavigateBack     Pops back to the caller — see [SearchContent] for why the
 *                           affordance is phone-only.
 * @param onNavigateToDetail Opens the detail screen for a clicked result.
 */
@Composable
fun SearchScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (contentType: String, contentId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SearchContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onQueryChange = viewModel::onQueryChange,
        onResultClick = { item -> onNavigateToDetail(item.contentType.toDetailContentType(), item.id) },
        onRetry = viewModel::onRetry,
        onLanguageSelected = viewModel::onLanguageSelected,
        modifier = modifier,
    )
}

/** Maps the domain [ContentType] to the string contract expected by [com.bobot.iptvapp.navigation.Detail.contentType]. */
private fun ContentType.toDetailContentType(): String = when (this) {
    ContentType.LIVE   -> "live"
    ContentType.MOVIE  -> "movie"
    ContentType.SERIES -> "series"
}

/** Local UI-only content-type filter — does not touch the ViewModel. */
private enum class SearchFilter { ALL, LIVE, MOVIE, SERIES }

/**
 * Stateless content — separated from [SearchScreen] so it can be exercised directly in
 * `@Preview`s without a Hilt ViewModel.
 *
 * ## Back affordance (QA finding N8)
 * The header row is rendered on phones only. Search is reached from the home top bar and used to
 * be the one destination with no way out on a gesture-navigation phone, where Downloads — reached
 * from the same bar — has always had a "Retour" button. A TV has a physical Back key, and adding
 * a button there would only insert one more focus stop above the query field.
 *
 * ## Explicit downward focus order (QA finding N14)
 * On TV, a `DOWN` from the query field used to land on the language row, skipping the content-type
 * chips (which were still reachable by coming back *up* from the languages). Rather than rely on
 * Compose's geometric focus search across three stacked lazy rows, the field now declares its
 * `down` target explicitly. [FocusRequester.Default] restores the default search whenever the
 * type row is not composed — pointing `down` at a requester attached to nothing throws.
 */
@Composable
private fun SearchContent(
    uiState: SearchUiState,
    onNavigateBack: () -> Unit = { },
    onQueryChange: (String) -> Unit,
    onResultClick: (SearchResultItem) -> Unit,
    onRetry: () -> Unit,
    onLanguageSelected: (String?) -> Unit = { },
    modifier: Modifier = Modifier,
) {
    val isTv = rememberIsTvDevice()
    val horizontalPadding = if (isTv) LayoutDimens.ContentPaddingTv else LayoutDimens.ContentPaddingPhone
    val queryFocusRequester = remember { FocusRequester() }
    val typeFilterFocusRequester = remember { FocusRequester() }
    var activeFilter by remember { mutableStateOf(SearchFilter.ALL) }

    val typeFilterRowVisible = uiState.hasAnyResults || uiState.query.isNotBlank()

    LaunchedEffect(Unit) {
        runCatching { queryFocusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase)
            .statusBarsPadding(),
    ) {
        if (!isTv) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                FocusableTextButton(label = "Retour", onClick = onNavigateBack)
                Text(
                    text = "Recherche",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
            }
        }

        // Glass search field
        SearchField(
            query = uiState.query,
            onQueryChange = onQueryChange,
            focusRequester = queryFocusRequester,
            downFocusRequester =
                if (typeFilterRowVisible) typeFilterFocusRequester else FocusRequester.Default,
            horizontalPadding = horizontalPadding,
        )

        // CategoryChip filter row
        if (typeFilterRowVisible) {
            LazyRow(
                contentPadding = PaddingValues(
                    horizontal = horizontalPadding,
                    vertical = LayoutDimens.LazyRowFocusPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    CategoryChip(
                        label = "Tout",
                        selected = activeFilter == SearchFilter.ALL,
                        onClick = { activeFilter = SearchFilter.ALL },
                        // The chip a DOWN from the query field must land on — see the KDoc.
                        modifier = Modifier.focusRequester(typeFilterFocusRequester),
                    )
                }
                item {
                    CategoryChip(
                        label = "Chaînes",
                        selected = activeFilter == SearchFilter.LIVE,
                        onClick = { activeFilter = SearchFilter.LIVE },
                    )
                }
                item {
                    CategoryChip(
                        label = "Films",
                        selected = activeFilter == SearchFilter.MOVIE,
                        onClick = { activeFilter = SearchFilter.MOVIE },
                    )
                }
                item {
                    CategoryChip(
                        label = "Séries",
                        selected = activeFilter == SearchFilter.SERIES,
                        onClick = { activeFilter = SearchFilter.SERIES },
                    )
                }
            }
        }

        SearchLanguageFilterRow(
            languages = uiState.availableLanguages,
            selected = uiState.selectedLanguage,
            horizontalPadding = horizontalPadding,
            onLanguageSelected = onLanguageSelected,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.query.isBlank() -> SearchEmptyQueryState()

                uiState.isLoading && !uiState.hasAnyResults -> SearchLoadingState()

                uiState.errorMessage != null && !uiState.hasAnyResults -> SearchErrorState(
                    message = uiState.errorMessage,
                    onRetry = onRetry,
                )

                uiState.hasAnyResults -> SearchResultsContent(
                    uiState = uiState,
                    activeFilter = activeFilter,
                    horizontalPadding = horizontalPadding,
                    onResultClick = onResultClick,
                    onRetry = onRetry,
                )

                else -> SearchNoResultsState(query = uiState.query)
            }
        }
    }
}

/**
 * Single, global language filter chip row (Task 5) — one "Toutes" [CategoryChip] (clears the
 * filter, `selected = selected == null`) plus one chip per entry in [languages].
 *
 * Unlike [com.bobot.iptvapp.ui.screen.home.HomeScreen]'s equivalent
 * (`homeLanguageFilterRow`, Task 3), which is a `LazyListScope` extension added as an `item {}`
 * inside a `LazyColumn` per Home tab, this is a plain `@Composable` because [SearchContent] places
 * its chip rows directly in a [Column] (see the pre-existing content-type `CategoryChip` `LazyRow`
 * above) rather than inside a `LazyColumn` — Search combines Live/Movies/Series into a single
 * scrollable [SearchResultsContent], so there is only one language selector for all three types,
 * not one per tab like Home.
 *
 * No-ops when [languages] is empty, mirroring `homeLanguageFilterRow`'s early return: a lone
 * "Toutes" chip with nothing else to filter by would add visual noise with no value.
 *
 * Selecting a chip only calls [onLanguageSelected] with the chip's language (or `null` for
 * "Toutes") — filtering of [SearchUiState.liveResults]/[SearchUiState.movieResults]/
 * [SearchUiState.seriesResults] already happened upstream in [SearchViewModel] (see that class's
 * KDoc "Global language filter"), so no client-side filtering happens here.
 */
@Composable
private fun SearchLanguageFilterRow(
    languages: List<String>,
    selected: String?,
    horizontalPadding: Dp,
    onLanguageSelected: (String?) -> Unit,
) {
    if (languages.isEmpty()) return

    LazyRow(
        contentPadding = PaddingValues(
            horizontal = horizontalPadding,
            vertical = LayoutDimens.LazyRowFocusPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "search-language-all") {
            CategoryChip(
                label = "Toutes",
                selected = selected == null,
                onClick = { onLanguageSelected(null) },
            )
        }
        items(languages, key = { language -> "search-language-$language" }) { language ->
            CategoryChip(
                label = language,
                selected = selected == language,
                onClick = { onLanguageSelected(language) },
            )
        }
    }
}

// ─── Glass search field ──────────────────────────────────────────────────────

/**
 * @param downFocusRequester Where a `DOWN` from the field goes. Pass [FocusRequester.Default] to
 *                           fall back to Compose's own focus search — see [SearchContent]'s KDoc
 *                           (QA finding N14).
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    horizontalPadding: Dp,
) {
    // Wrap in glassSurface to give the field a glass backing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = Spacing.md)
            .glassSurface(shape = RoundedCornerShape(RadiusMd)),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Rechercher") },
            placeholder = { Text("Film, série ou chaîne…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = searchTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusProperties { down = downFocusRequester }
                .dpadFocusEscape(),
        )
    }
}

/** Glass-themed colours for [SearchField] — AccentSolid focus ring, dimmed unfocused. */
@Composable
private fun searchTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentSolid,
    unfocusedBorderColor = TextDimmed,
    focusedLabelColor = TextPrimary,
    unfocusedLabelColor = TextSecondary,
    cursorColor = AccentSolid,
    focusedPlaceholderColor = TextDimmed,
    unfocusedPlaceholderColor = TextDimmed,
    // Transparent container so the glassSurface from the Box shows through
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
)

// ─── Loading / error / empty-query / no-results states ──────────────────────

@Composable
private fun SearchEmptyQueryState() {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Text(
            text = "Recherchez un film, une série ou une chaîne.",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AccentSolid)
    }
}

@Composable
private fun SearchErrorState(
    message: String?,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Impossible de charger le catalogue.",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticError,
                )
            }
            FocusableTextButton(label = "Réessayer", onClick = onRetry)
        }
    }
}

@Composable
private fun SearchNoResultsState(query: String) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Text(
            text = "Aucun résultat pour « $query ».",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── Results content ─────────────────────────────────────────────────────────

/**
 * Renders Chaînes / Films / Séries sections filtered by [activeFilter].
 * Each section is a [SectionTitle] + [LazyRow] of [FocusableCard]s.
 * LazyRow uses vertical contentPadding = [LayoutDimens.LazyRowFocusPadding] so focus
 * lift / glow is not clipped (T7 review fix).
 */
@Composable
private fun SearchResultsContent(
    uiState: SearchUiState,
    activeFilter: SearchFilter,
    horizontalPadding: Dp,
    onResultClick: (SearchResultItem) -> Unit,
    onRetry: () -> Unit,
) {
    val isTv = rememberIsTvDevice()
    val cardWidth = if (isTv) CardDimens.PosterWidthTv else CardDimens.PosterWidthPhone

    val showLive   = activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.LIVE
    val showMovies = activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.MOVIE
    val showSeries = activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.SERIES

    // Detect the case where a category filter is active and yields no results,
    // while other categories DO have results (hasAnyResults is true overall).
    val filteredSectionsEmpty = activeFilter != SearchFilter.ALL && run {
        val filteredItems = when (activeFilter) {
            SearchFilter.LIVE   -> uiState.liveResults
            SearchFilter.MOVIE  -> uiState.movieResults
            SearchFilter.SERIES -> uiState.seriesResults
            SearchFilter.ALL    -> emptyList()
        }
        filteredItems.isEmpty()
    }

    if (filteredSectionsEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Aucun résultat dans cette catégorie.",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    } else LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
    ) {
        if (uiState.errorMessage != null) {
            item(key = "error-banner") {
                SearchErrorBanner(
                    message = uiState.errorMessage,
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.sm),
                )
            }
        }

        if (showLive) {
            searchSection(
                sectionTitle = "Chaînes",
                resultItems = uiState.liveResults,
                stillLoading = uiState.isLoading,
                horizontalPadding = horizontalPadding,
                cardWidth = cardWidth,
                onResultClick = onResultClick,
            )
        }

        if (showMovies) {
            searchSection(
                sectionTitle = "Films",
                resultItems = uiState.movieResults,
                stillLoading = uiState.isLoading,
                horizontalPadding = horizontalPadding,
                cardWidth = cardWidth,
                onResultClick = onResultClick,
            )
        }

        if (showSeries) {
            searchSection(
                sectionTitle = "Séries",
                resultItems = uiState.seriesResults,
                stillLoading = uiState.isLoading,
                horizontalPadding = horizontalPadding,
                cardWidth = cardWidth,
                onResultClick = onResultClick,
            )
        }
    }
}

/** Non-blocking banner shown above results when one section failed but others already matched. */
@Composable
private fun SearchErrorBanner(
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundElevated)
            .padding(Spacing.md),
    ) {
        Column {
            Text(
                text = message?.takeIf { it.isNotBlank() } ?: "Une partie du catalogue n'a pas pu être chargée.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            FocusableTextButton(label = "Réessayer", onClick = onRetry)
        }
    }
}

/**
 * Adds one section (header + a single lazy row) to the enclosing [LazyColumn].
 * Uses vertical [LayoutDimens.LazyRowFocusPadding] on the LazyRow so focused card
 * glow/scale is not clipped at the row boundaries.
 *
 * ## Per-section progress (QA finding N9)
 * The search walks the catalog category by category, so the three sections fill in one after the
 * other. The section used to be skipped entirely while it was still empty, which is why results
 * seemed to pop in at random with nothing to explain the wait.
 *
 * While [stillLoading] is `true` the section is therefore always rendered: its header carries a
 * thin indeterminate bar, and an empty section shows "Recherche en cours…" instead of vanishing.
 * Once the search settles, an empty section disappears exactly as before — a permanently empty
 * "Séries" header would be noise.
 */
private fun LazyListScope.searchSection(
    sectionTitle: String,
    resultItems: List<SearchResultItem>,
    stillLoading: Boolean,
    horizontalPadding: Dp,
    cardWidth: Dp,
    onResultClick: (SearchResultItem) -> Unit,
) {
    if (resultItems.isEmpty() && !stillLoading) return

    item(key = "section-$sectionTitle") {
        SectionTitle(
            title = sectionTitle,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.xs),
            trailingAction = if (stillLoading) {
                {
                    LinearProgressIndicator(
                        color = AccentSolid,
                        trackColor = BackgroundElevated,
                        modifier = Modifier.width(56.dp),
                    )
                }
            } else {
                null
            },
        )
    }

    if (resultItems.isEmpty()) {
        item(key = "pending-$sectionTitle") {
            Text(
                text = "Recherche en cours…",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.sm),
            )
        }
        return
    }

    item(key = "row-$sectionTitle") {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(LayoutDimens.CardRowSpacing),
            contentPadding = PaddingValues(
                horizontal = horizontalPadding,
                vertical = LayoutDimens.LazyRowFocusPadding,
            ),
        ) {
            items(resultItems, key = { it.id }) { resultItem ->
                FocusableCard(
                    title = resultItem.title,
                    imageUrl = resultItem.imageUrl,
                    onClick = { onResultClick(resultItem) },
                    modifier = Modifier.width(cardWidth),
                )
            }
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private val previewLiveResults = listOf(
    SearchResultItem(id = "l1", title = "Chaîne Sport 1", imageUrl = null, contentType = ContentType.LIVE),
)

private val previewMovieResults = listOf(
    SearchResultItem(id = "m1", title = "Explosion Totale", imageUrl = null, contentType = ContentType.MOVIE),
)

@Preview(name = "Search — empty query", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun SearchContentEmptyQueryPreview() {
    IptvAppTheme {
        SearchContent(
            uiState = SearchUiState(query = "", isLoading = false),
            onQueryChange = {},
            onResultClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Search — results", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun SearchContentResultsPreview() {
    IptvAppTheme {
        SearchContent(
            uiState = SearchUiState(
                query = "e",
                liveResults = previewLiveResults,
                movieResults = previewMovieResults,
                isLoading = false,
            ),
            onQueryChange = {},
            onResultClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Search — no results", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun SearchContentNoResultsPreview() {
    IptvAppTheme {
        SearchContent(
            uiState = SearchUiState(query = "xyz", isLoading = false),
            onQueryChange = {},
            onResultClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Search — loading", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun SearchContentLoadingPreview() {
    IptvAppTheme {
        SearchContent(
            uiState = SearchUiState(query = "a", isLoading = true),
            onQueryChange = {},
            onResultClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Search — error", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun SearchContentErrorPreview() {
    IptvAppTheme {
        SearchContent(
            uiState = SearchUiState(query = "a", isLoading = false, errorMessage = "Connexion au serveur impossible."),
            onQueryChange = {},
            onResultClick = {},
            onRetry = {},
        )
    }
}
