package com.bobot.iptvapp.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.ui.components.CategoryChip
import com.bobot.iptvapp.ui.components.FocusableCard
import com.bobot.iptvapp.ui.components.FocusableTextButton
import com.bobot.iptvapp.ui.components.GhostButton
import com.bobot.iptvapp.ui.components.GlassIconButton
import com.bobot.iptvapp.ui.components.GlassSurface
import com.bobot.iptvapp.ui.components.PrimaryButton
import com.bobot.iptvapp.ui.components.SectionTitle
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.BackgroundElevated
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.LayoutDimens
import com.bobot.iptvapp.ui.theme.RadiusLg
import com.bobot.iptvapp.ui.theme.RadiusXl
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.SemanticLive
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import com.bobot.iptvapp.ui.util.rememberIsTvDevice

/**
 * Home screen (Task 17 + Task 22 + Task 23 + Task 9 reskin) — replaces the former
 * `HomePlaceholderScreen` stub. Reskinned to "Cinematic Glass" V2 language in Task 9.
 *
 * Renders an immersive glass hero followed by horizontal category rows for the five
 * content sections. Every row and the hero reuse T6/T7/T8 components and tokens.
 *
 * @param onNavigateToDetail   Opens the detail screen for a clicked card.
 * @param onNavigateToPlayer   Opens the player directly for a "Reprendre" card.
 * @param onNavigateToSearch   Opens the search screen.
 * @param onNavigateToSettings Opens the settings screen.
 */
@Composable
fun HomeScreen(
    onNavigateToDetail: (contentType: String, contentId: String) -> Unit,
    onNavigateToPlayer: (streamUrl: String, streamId: String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeContent(
        uiState = uiState,
        onCardClick = { item ->
            val resumeUrl = item.resumeStreamUrl
            if (resumeUrl != null) {
                onNavigateToPlayer(resumeUrl, item.id)
            } else {
                onNavigateToDetail(item.contentType.toDetailContentType(), item.id)
            }
        },
        onNavigateToDetail = onNavigateToDetail,
        onNavigateToSearch = onNavigateToSearch,
        onNavigateToSettings = onNavigateToSettings,
        onRetry = viewModel::onRetry,
        onCatalogTabSelected = viewModel::onCatalogTabSelected,
        onLanguageSelected = viewModel::onLanguageSelected,
        modifier = modifier,
    )
}

/** Maps the domain [ContentType] to the string contract expected by the Detail route. */
private fun ContentType.toDetailContentType(): String = when (this) {
    ContentType.LIVE -> "live"
    ContentType.MOVIE -> "movie"
    ContentType.SERIES -> "series"
}

// --- Home tabs (Task 1) ---

/**
 * Local, UI-only home catalog tab — never touches [HomeViewModel] or [HomeUiState].
 * Ordinal order defines both the [TabRow] display order and the initial selection ([HOME]).
 *
 *  - [HOME]   — hero + "Reprendre" (continue watching) + "Ma liste" (favorites), the
 *               pre-existing Home behavior.
 *  - [LIVE]   — only [HomeUiState.liveRows].
 *  - [MOVIES] — only [HomeUiState.movieRows].
 *  - [SERIES] — only [HomeUiState.seriesRows].
 */
internal enum class HomeTab(val label: String) {
    HOME("Accueil"),
    LIVE("Chaines"),
    MOVIES("Films"),
    SERIES("Series"),
}

/**
 * Maps a catalog [HomeTab] to the [ContentType] whose on-demand loading it triggers via
 * [HomeViewModel.onCatalogTabSelected] (see [HomeContent]'s `LaunchedEffect(selectedTab)`), or
 * `null` for [HomeTab.HOME] — the Home tab's rows (Reprendre/Ma liste) are not gated behind any
 * catalog tab selection (see [HomeViewModel] KDoc "On-demand catalog loading").
 */
private fun HomeTab.toContentTypeOrNull(): ContentType? = when (this) {
    HomeTab.HOME -> null
    HomeTab.LIVE -> ContentType.LIVE
    HomeTab.MOVIES -> ContentType.MOVIE
    HomeTab.SERIES -> ContentType.SERIES
}

/** The rows rendered as horizontal category rows for [tab] (excludes the hero — see [heroItemFor]). */
private fun HomeUiState.rowsFor(tab: HomeTab): List<HomeRow> = when (tab) {
    HomeTab.HOME -> continueWatchingRows + myListRows
    HomeTab.LIVE -> liveRows
    HomeTab.MOVIES -> movieRows
    HomeTab.SERIES -> seriesRows
}

/**
 * Hero banner item for [tab] — only the [HomeTab.HOME] tab ever shows a hero, matching the
 * pre-existing behavior (a movie, then series, then live highlight, in that priority order).
 */
private fun HomeUiState.heroItemFor(tab: HomeTab): HomeCardItem? =
    if (tab != HomeTab.HOME) {
        null
    } else {
        movieRows.firstOrNull()?.items?.firstOrNull()
            ?: seriesRows.firstOrNull()?.items?.firstOrNull()
            ?: liveRows.firstOrNull()?.items?.firstOrNull()
    }

/** First card of [tab]'s own rows — used as the D-pad initial-focus fallback when [tab] has no hero. */
private fun HomeUiState.firstRowItemFor(tab: HomeTab): HomeCardItem? = when (tab) {
    HomeTab.HOME -> continueWatchingRows.firstOrNull()?.items?.firstOrNull()
        ?: myListRows.firstOrNull()?.items?.firstOrNull()
    else -> rowsFor(tab).firstOrNull()?.items?.firstOrNull()
}

/**
 * Selected category row for a catalog [tab], or the first available row when the current
 * selection is null/stale. [HomeTab.HOME] never uses category selection.
 */
private fun HomeUiState.selectedCategoryRowFor(
    tab: HomeTab,
    selectedCategoryId: String?,
): HomeRow? {
    if (tab == HomeTab.HOME) return null
    val rows = rowsFor(tab)
    return rows.firstOrNull { it.categoryId == selectedCategoryId } ?: rows.firstOrNull()
}

/** Selected category id for a catalog [tab], normalized to the first available row when stale/null. */
internal fun HomeUiState.normalizedCategorySelectionFor(
    tab: HomeTab,
    selectedCategoryId: String?,
): String? = selectedCategoryRowFor(tab, selectedCategoryId)?.categoryId

/**
 * Initial D-pad focus target for [tab]. Catalog tabs must target the selected category's first
 * card — not always the first category overall — to preserve the Unit B focus regression fix.
 */
internal fun HomeUiState.initialFocusItemFor(
    tab: HomeTab,
    selectedCategoryId: String?,
): HomeCardItem? {
    val heroItem = heroItemFor(tab)
    if (heroItem != null) return null

    return if (tab == HomeTab.HOME) {
        firstRowItemFor(tab)
    } else {
        selectedCategoryRowFor(tab, selectedCategoryId)?.items?.firstOrNull() ?: firstRowItemFor(tab)
    }
}

/** `true` once [tab] has a hero or at least one row — drives per-tab loading/error/empty selection. */
private fun HomeUiState.hasContentFor(tab: HomeTab): Boolean =
    heroItemFor(tab) != null || rowsFor(tab).isNotEmpty()

/**
 * Stateless content — separated from [HomeScreen] so it can be exercised directly in
 * @Preview without a Hilt ViewModel.
 *
 * ## Task 1 — home tabs
 * Restructured around a local [HomeTab] selection: the loading/error/empty vs. rows-content
 * choice below is now evaluated against the *active tab's* content ([HomeUiState.hasContentFor])
 * rather than the whole [HomeUiState] ([HomeUiState.hasAnyRows] is no longer used here). The
 * [HomeHeader] (title row + [TabRow]) is always floated above whichever state is showing so the
 * user can switch away from a still-loading or empty tab into one that already has content —
 * sections load independently (see [HomeViewModel] "On-demand catalog loading").
 *
 * ## On-demand catalog loading (OOM fix)
 * A `LaunchedEffect(selectedTab)` below calls [onCatalogTabSelected] with the [ContentType]
 * mapped from [selectedTab] (see [toContentTypeOrNull]) every time the user switches tabs,
 * ignored for [HomeTab.HOME] (`null` mapping — Home's rows are not gated behind a catalog tab
 * selection, see [HomeViewModel] KDoc). [HomeViewModel.onCatalogTabSelected] is itself idempotent
 * per content type, so re-selecting an already-loaded tab (including recomposition re-running this
 * effect) is a no-op.
 */
@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onCardClick: (HomeCardItem) -> Unit,
    onNavigateToDetail: (contentType: String, contentId: String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onCatalogTabSelected: (ContentType) -> Unit = {},
    onLanguageSelected: (ContentType, String?) -> Unit = { _, _ -> },
) {
    var selectedTab by remember { mutableStateOf(HomeTab.HOME) }
    val hasTabContent = uiState.hasContentFor(selectedTab)

    LaunchedEffect(selectedTab) {
        selectedTab.toContentTypeOrNull()?.let(onCatalogTabSelected)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase),
    ) {
        when {
            uiState.isLoading && !hasTabContent -> HomeLoadingState()

            uiState.errorMessage != null && !hasTabContent -> HomeErrorState(
                message = uiState.errorMessage,
                onRetry = onRetry,
            )

            !hasTabContent -> HomeEmptyState()

            else -> HomeRowsContent(
                uiState = uiState,
                selectedTab = selectedTab,
                onCardClick = onCardClick,
                onNavigateToDetail = onNavigateToDetail,
                onRetry = onRetry,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToSettings = onNavigateToSettings,
                onTabSelected = { selectedTab = it },
                onLanguageSelected = onLanguageSelected,
            )
        }

        // HomeRowsContent renders its own scroll-reactive header (shares the hero's collapse
        // fraction). The full-screen states above have no scrolling content of their own, so
        // float a solid (non-collapsing) header above them here instead.
        if (!hasTabContent) {
            HomeHeader(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToSettings = onNavigateToSettings,
                collapseFraction = 1f,
            )
        }
    }
}

// --- Top bar + tabs (Task 1) ---

/**
 * Floating header combining [HomeTopBar] (title + search/settings actions) and [HomeTabBar]
 * (Accueil / Chaines / Films / Series). Always rendered above the active body — see
 * [HomeContent] and [HomeRowsContent] — so the user can switch tabs regardless of whether the
 * active tab is loading, empty, errored, or showing rows.
 */
@Composable
private fun HomeHeader(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    collapseFraction: Float,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HomeTopBar(
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToSettings = onNavigateToSettings,
            collapseFraction = collapseFraction,
        )
        HomeTabBar(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            // The top bar's scrim already fades toward transparent near its bottom edge
            // (see topBarScrim below) so tab labels can lose contrast against a bright hero
            // frame at collapseFraction 0. Floor the tab bar's own background at 75% opacity
            // so the tabs stay legible and tappable at every scroll position.
            containerColor = BackgroundBase.copy(alpha = maxOf(0.75f, collapseFraction)),
        )
    }
}

@Composable
private fun HomeTopBar(
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    collapseFraction: Float,
) {
    val isTv = rememberIsTvDevice()
    val horizontalPadding = if (isTv) LayoutDimens.ContentPaddingTv else LayoutDimens.ContentPaddingPhone

    // Scroll-reactive background: at the top (collapseFraction 0) a soft top-down scrim
    // keeps the nav readable over the hero; once scrolled (fraction 1) every stop reaches
    // full opacity, giving a flat, solid bar. Interpolating the bottom stop toward opaque
    // avoids content bleeding under the bar as the user scrolls.
    val topBarScrim = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to BackgroundBase.copy(alpha = maxOf(0.92f, collapseFraction)),
            0.60f to BackgroundBase.copy(alpha = maxOf(0.55f, collapseFraction)),
            1.00f to BackgroundBase.copy(alpha = collapseFraction),
        ),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(topBarScrim)
            .statusBarsPadding()
            .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Accueil",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FocusableTextButton(label = "Recherche", onClick = onNavigateToSearch)
            FocusableTextButton(label = "Reglages", onClick = onNavigateToSettings)
        }
    }
}

/**
 * Home catalog tab bar — Accueil / Chaines / Films / Series (Task 1). Local UI-only
 * selection: never reads from or writes to [HomeViewModel] / [HomeUiState].
 */
@Composable
private fun HomeTabBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = BackgroundBase,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        modifier = modifier.fillMaxWidth(),
        containerColor = containerColor,
        contentColor = AccentSolid,
    ) {
        HomeTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                selectedContentColor = AccentSolid,
                unselectedContentColor = TextSecondary,
            )
        }
    }
}

// --- Loading / error / empty states ---

@Composable
private fun HomeLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = AccentSolid,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun HomeErrorState(
    message: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.padding(Spacing.lg),
            shape = RoundedCornerShape(RadiusLg),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Impossible de charger le catalogue.",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                if (!message.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticError,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
                PrimaryButton(label = "Reessayer", onClick = onRetry)
            }
        }
    }
}

@Composable
private fun HomeEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Aucun contenu disponible pour le moment.",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
        )
    }
}

// --- Content (hero + rows) ---

/**
 * Renders the optional hero banner ([HomeHero]) followed by the [selectedTab]'s category rows,
 * inside a single [LazyColumn]. The header ([HomeHeader]: title row + [HomeTabBar]) floats as
 * an overlay above the hero so the hero image extends edge-to-edge behind it.
 *
 * ## Task 1 — home tabs
 * Only [selectedTab]'s sections are added to the [LazyColumn] (see [HomeUiState.rowsFor] /
 * [HomeUiState.heroItemFor]):
 *  - [HomeTab.HOME]: hero + "Reprendre" + "Ma liste" — unchanged pre-existing behavior.
 *  - [HomeTab.LIVE] / [HomeTab.MOVIES] / [HomeTab.SERIES]: only that tab's own rows, no hero.
 *
 * The [LazyListState] and initial D-pad [FocusRequester] are both re-created per [selectedTab]
 * (keyed on it) so switching tabs starts each tab's list at the top and re-targets the initial
 * focus at that tab's own hero/first card instead of a global, Home-tab-only target.
 */
@Composable
private fun HomeRowsContent(
    uiState: HomeUiState,
    selectedTab: HomeTab,
    onCardClick: (HomeCardItem) -> Unit,
    onNavigateToDetail: (contentType: String, contentId: String) -> Unit,
    onRetry: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTabSelected: (HomeTab) -> Unit,
    onLanguageSelected: (ContentType, String?) -> Unit,
) {
    val isTv = rememberIsTvDevice()
    val horizontalPadding = if (isTv) LayoutDimens.ContentPaddingTv else LayoutDimens.ContentPaddingPhone
    val configuration = LocalConfiguration.current
    val cardWidth = if (isTv) {
        CardDimens.PosterWidthTv
    } else {
        ((configuration.screenWidthDp.dp - (horizontalPadding * 2) - LayoutDimens.CardRowSpacing) / 2)
            .coerceAtLeast(CardDimens.PosterWidthPhone)
    }
    val categoryGridColumns = if (isTv) 4 else 2

    val heroItem = uiState.heroItemFor(selectedTab)

    var selectedLiveCategoryId by remember { mutableStateOf<String?>(null) }
    var selectedMovieCategoryId by remember { mutableStateOf<String?>(null) }
    var selectedSeriesCategoryId by remember { mutableStateOf<String?>(null) }

    val rawSelectedCategoryId = when (selectedTab) {
        HomeTab.HOME -> null
        HomeTab.LIVE -> selectedLiveCategoryId
        HomeTab.MOVIES -> selectedMovieCategoryId
        HomeTab.SERIES -> selectedSeriesCategoryId
    }
    val selectedCategoryId = uiState.normalizedCategorySelectionFor(selectedTab, rawSelectedCategoryId)
    val selectedCategoryRow = uiState.selectedCategoryRowFor(selectedTab, selectedCategoryId)

    val initialFocusRequester = remember(selectedTab) { FocusRequester() }
    val rowsFocusTarget = uiState.initialFocusItemFor(selectedTab, selectedCategoryId)

    // Scroll-driven header background: transparent scrim while the first item (hero, when
    // present) is at the top, fading to a fully solid bar once the user scrolls past it.
    // Re-created per selectedTab so each tab starts fresh at the top of its own list.
    val listState = remember(selectedTab) { LazyListState() }
    val density = LocalDensity.current
    val collapseThresholdPx = remember(density) { with(density) { 200.dp.toPx() } }
    val topBarCollapseFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / collapseThresholdPx).coerceIn(0f, 1f)
            }
        }
    }

    LaunchedEffect(
        selectedTab,
        uiState.continueWatchingRows,
        uiState.myListRows,
        uiState.liveRows,
        uiState.movieRows,
        uiState.seriesRows,
    ) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    LaunchedEffect(uiState.selectedLiveLanguage) {
        selectedLiveCategoryId = null
    }

    LaunchedEffect(uiState.selectedMovieLanguage) {
        selectedMovieCategoryId = null
    }

    LaunchedEffect(uiState.selectedSeriesLanguage) {
        selectedSeriesCategoryId = null
    }

    LaunchedEffect(selectedTab, uiState.liveRows, uiState.movieRows, uiState.seriesRows) {
        when (selectedTab) {
            HomeTab.HOME -> Unit
            HomeTab.LIVE -> {
                val normalized = uiState.normalizedCategorySelectionFor(HomeTab.LIVE, selectedLiveCategoryId)
                if (normalized != selectedLiveCategoryId) {
                    selectedLiveCategoryId = normalized
                }
            }
            HomeTab.MOVIES -> {
                val normalized = uiState.normalizedCategorySelectionFor(HomeTab.MOVIES, selectedMovieCategoryId)
                if (normalized != selectedMovieCategoryId) {
                    selectedMovieCategoryId = normalized
                }
            }
            HomeTab.SERIES -> {
                val normalized = uiState.normalizedCategorySelectionFor(HomeTab.SERIES, selectedSeriesCategoryId)
                if (normalized != selectedSeriesCategoryId) {
                    selectedSeriesCategoryId = normalized
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            if (heroItem != null) {
                item(key = "hero") {
                    HomeHero(
                        item = heroItem,
                        onClick = { onCardClick(heroItem) },
                        onDetailClick = {
                            onNavigateToDetail(
                                heroItem.contentType.toDetailContentType(),
                                heroItem.id,
                            )
                        },
                        focusRequester = initialFocusRequester,
                    )
                }
            } else {
                // Reserve space for the floating header (title row + tab bar) when there is no hero
                item(key = "topbar-spacer") {
                    Spacer(
                        modifier = Modifier
                            .statusBarsPadding()
                            .height(LayoutDimens.TopBarHeight + LayoutDimens.TabRowHeight),
                    )
                }
            }

            if (uiState.errorMessage != null) {
                item(key = "error-banner") {
                    HomeErrorBanner(
                        message = uiState.errorMessage,
                        onRetry = onRetry,
                        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.sm),
                    )
                }
            }

            when (selectedTab) {
                HomeTab.HOME -> {
                    homeSection(
                        sectionTitle = "Reprendre",
                        rows = uiState.continueWatchingRows,
                        horizontalPadding = horizontalPadding,
                        cardWidth = cardWidth,
                        onCardClick = onCardClick,
                        initialFocusItem = rowsFocusTarget,
                        initialFocusRequester = initialFocusRequester,
                        isContinueWatching = true,
                    )

                    homeSection(
                        sectionTitle = "Ma liste",
                        rows = uiState.myListRows,
                        horizontalPadding = horizontalPadding,
                        cardWidth = cardWidth,
                        onCardClick = onCardClick,
                        initialFocusItem = rowsFocusTarget,
                        initialFocusRequester = initialFocusRequester,
                    )
                }

                HomeTab.LIVE -> {
                    homeLanguageFilterRow(
                        key = "language-filter-live",
                        languages = uiState.liveLanguages,
                        selected = uiState.selectedLiveLanguage,
                        horizontalPadding = horizontalPadding,
                        onLanguageSelected = { language -> onLanguageSelected(ContentType.LIVE, language) },
                    )
                    homeCategorySelectorRow(
                        key = "category-selector-live",
                        rows = uiState.liveRows,
                        selectedCategoryId = selectedCategoryRow?.categoryId,
                        horizontalPadding = horizontalPadding,
                        onCategorySelected = { selectedLiveCategoryId = it },
                    )
                    homeCategoryGridSection(
                        sectionTitle = selectedCategoryRow?.title ?: "En direct",
                        row = selectedCategoryRow,
                        columns = categoryGridColumns,
                        horizontalPadding = horizontalPadding,
                        cardWidth = cardWidth,
                        onCardClick = onCardClick,
                        initialFocusItem = rowsFocusTarget,
                        initialFocusRequester = initialFocusRequester,
                        isLive = true,
                    )
                }

                HomeTab.MOVIES -> {
                    homeLanguageFilterRow(
                        key = "language-filter-movies",
                        languages = uiState.movieLanguages,
                        selected = uiState.selectedMovieLanguage,
                        horizontalPadding = horizontalPadding,
                        onLanguageSelected = { language -> onLanguageSelected(ContentType.MOVIE, language) },
                    )
                    homeCategorySelectorRow(
                        key = "category-selector-movies",
                        rows = uiState.movieRows,
                        selectedCategoryId = selectedCategoryRow?.categoryId,
                        horizontalPadding = horizontalPadding,
                        onCategorySelected = { selectedMovieCategoryId = it },
                    )
                    homeCategoryGridSection(
                        sectionTitle = selectedCategoryRow?.title ?: "Films",
                        row = selectedCategoryRow,
                        columns = categoryGridColumns,
                        horizontalPadding = horizontalPadding,
                        cardWidth = cardWidth,
                        onCardClick = onCardClick,
                        initialFocusItem = rowsFocusTarget,
                        initialFocusRequester = initialFocusRequester,
                    )
                }

                HomeTab.SERIES -> {
                    homeLanguageFilterRow(
                        key = "language-filter-series",
                        languages = uiState.seriesLanguages,
                        selected = uiState.selectedSeriesLanguage,
                        horizontalPadding = horizontalPadding,
                        onLanguageSelected = { language -> onLanguageSelected(ContentType.SERIES, language) },
                    )
                    homeCategorySelectorRow(
                        key = "category-selector-series",
                        rows = uiState.seriesRows,
                        selectedCategoryId = selectedCategoryRow?.categoryId,
                        horizontalPadding = horizontalPadding,
                        onCategorySelected = { selectedSeriesCategoryId = it },
                    )
                    homeCategoryGridSection(
                        sectionTitle = selectedCategoryRow?.title ?: "Series",
                        row = selectedCategoryRow,
                        columns = categoryGridColumns,
                        horizontalPadding = horizontalPadding,
                        cardWidth = cardWidth,
                        onCardClick = onCardClick,
                        initialFocusItem = rowsFocusTarget,
                        initialFocusRequester = initialFocusRequester,
                    )
                }
            }
        }

        // Header floats above the LazyColumn so the hero image extends full-bleed underneath
        HomeHeader(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToSettings = onNavigateToSettings,
            collapseFraction = topBarCollapseFraction,
        )
    }
}

/** Non-blocking banner shown above the rows when one section failed but others already loaded. */
@Composable
private fun HomeErrorBanner(
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusLg),
        strong = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message?.takeIf { it.isNotBlank() }
                    ?: "Une partie du catalogue n'a pas pu etre chargee.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            FocusableTextButton(label = "Reessayer", onClick = onRetry)
        }
    }
}

/**
 * Adds one section (header + category rows) to the enclosing [LazyColumn].
 * No-ops when [rows] is empty.
 */
private fun LazyListScope.homeSection(
    sectionTitle: String,
    rows: List<HomeRow>,
    horizontalPadding: Dp,
    cardWidth: Dp,
    onCardClick: (HomeCardItem) -> Unit,
    initialFocusItem: HomeCardItem?,
    initialFocusRequester: FocusRequester,
    isContinueWatching: Boolean = false,
    isLive: Boolean = false,
) {
    if (rows.isEmpty()) return

    item(key = "section-$sectionTitle") {
        SectionTitle(
            title = sectionTitle,
            modifier = Modifier.padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = Spacing.lg,
                bottom = Spacing.xs,
            ),
        )
    }

    items(rows, key = { "row-$sectionTitle-${it.categoryId}" }) { row ->
        HomeCategoryRow(
            row = row,
            horizontalPadding = horizontalPadding,
            cardWidth = cardWidth,
            onCardClick = onCardClick,
            initialFocusItem = initialFocusItem,
            initialFocusRequester = initialFocusRequester,
            isContinueWatching = isContinueWatching,
            isLive = isLive,
        )
    }
}

/**
 * Adds one language filter chip row to the enclosing [LazyColumn], for a Chaines/Films/Series
 * tab only (Task 3 — never rendered for [HomeTab.HOME], which has no language concept). One
 * "Toutes" [CategoryChip] (clears the filter, `selected = selected == null`) plus one chip per
 * entry in [languages] (the tab's distinct detected language tags — see
 * [com.bobot.iptvapp.domain.util.CategoryLanguage] via [HomeUiState.liveLanguages]/
 * [HomeUiState.movieLanguages]/[HomeUiState.seriesLanguages]).
 *
 * No-ops when [languages] is empty — mirrors [homeSection]'s early return: a lone "Toutes" chip
 * with nothing else to filter by would add visual noise with no value, so the whole row is
 * skipped until at least one language tag has been detected for that tab.
 *
 * Selecting a chip only calls [onLanguageSelected] with the chip's language (or `null` for
 * "Toutes") — the actual filtering already happened upstream, in [HomeViewModel], before
 * [HomeUiState.liveRows]/[HomeUiState.movieRows]/[HomeUiState.seriesRows] reached this screen
 * (see [HomeViewModel] KDoc "Per-tab language filter"), so no client-side filtering happens here.
 */
private fun LazyListScope.homeLanguageFilterRow(
    key: String,
    languages: List<String>,
    selected: String?,
    horizontalPadding: Dp,
    onLanguageSelected: (String?) -> Unit,
) {
    if (languages.isEmpty()) return

    item(key = key) {
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = horizontalPadding,
                vertical = LayoutDimens.LazyRowFocusPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "$key-all") {
                CategoryChip(
                    label = "Toutes",
                    selected = selected == null,
                    onClick = { onLanguageSelected(null) },
                )
            }
            items(languages, key = { language -> "$key-$language" }) { language ->
                CategoryChip(
                    label = language,
                    selected = selected == language,
                    onClick = { onLanguageSelected(language) },
                )
            }
        }
    }
}

/**
 * Adds one category selector chip row to the enclosing [LazyColumn], for a Chaines/Films/Series
 * tab only. One [CategoryChip] per loaded [HomeRow]; selecting a chip swaps the grid below to
 * that category's items instead of stacking multiple horizontal rows.
 */
private fun LazyListScope.homeCategorySelectorRow(
    key: String,
    rows: List<HomeRow>,
    selectedCategoryId: String?,
    horizontalPadding: Dp,
    onCategorySelected: (String) -> Unit,
) {
    if (rows.isEmpty()) return

    item(key = key) {
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = horizontalPadding,
                vertical = LayoutDimens.LazyRowFocusPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(rows, key = { row -> "$key-${row.categoryId}" }) { row ->
                CategoryChip(
                    label = row.title,
                    selected = selectedCategoryId == row.categoryId,
                    onClick = { onCategorySelected(row.categoryId) },
                )
            }
        }
    }
}

/**
 * Adds one selected-category section to the enclosing [LazyColumn]: a title followed by a
 * vertical poster grid built from that category's items.
 */
private fun LazyListScope.homeCategoryGridSection(
    sectionTitle: String,
    row: HomeRow?,
    columns: Int,
    horizontalPadding: Dp,
    cardWidth: Dp,
    onCardClick: (HomeCardItem) -> Unit,
    initialFocusItem: HomeCardItem?,
    initialFocusRequester: FocusRequester,
    isLive: Boolean = false,
) {
    if (row == null || row.items.isEmpty()) return

    item(key = "grid-section-$sectionTitle-${row.categoryId}") {
        SectionTitle(
            title = sectionTitle,
            modifier = Modifier.padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = Spacing.md,
                bottom = Spacing.xs,
            ),
        )
    }

    row.items.chunked(columns).forEachIndexed { index, itemsChunk ->
        item(key = "grid-$sectionTitle-${row.categoryId}-$index") {
            HomeCategoryGridRow(
                items = itemsChunk,
                columns = columns,
                horizontalPadding = horizontalPadding,
                cardWidth = cardWidth,
                onCardClick = onCardClick,
                initialFocusItem = initialFocusItem,
                initialFocusRequester = initialFocusRequester,
                isLive = isLive,
            )
        }
    }
}

/** One category row: a lazy horizontal row of [FocusableCard]s. */
@Composable
private fun HomeCategoryRow(
    row: HomeRow,
    horizontalPadding: Dp,
    cardWidth: Dp,
    onCardClick: (HomeCardItem) -> Unit,
    initialFocusItem: HomeCardItem?,
    initialFocusRequester: FocusRequester,
    isContinueWatching: Boolean = false,
    isLive: Boolean = false,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(LayoutDimens.CardRowSpacing),
        contentPadding = PaddingValues(
            horizontal = horizontalPadding,
            vertical = LayoutDimens.LazyRowFocusPadding,
        ),
    ) {
        items(row.items, key = { it.id }) { cardItem ->
            val cardModifier = if (initialFocusItem != null && cardItem == initialFocusItem) {
                Modifier
                    .width(cardWidth)
                    .focusRequester(initialFocusRequester)
            } else {
                Modifier.width(cardWidth)
            }

            // Continue-watching cards use landscape ratio; live cards show a LIVE badge.
            val liveBadge: (@Composable () -> Unit)? = if (isLive) {
                { LiveBadge() }
            } else {
                null
            }

            FocusableCard(
                title = cardItem.title,
                imageUrl = cardItem.imageUrl,
                onClick = { onCardClick(cardItem) },
                modifier = cardModifier,
                landscape = isContinueWatching,
                badge = liveBadge,
                // No progress fraction in HomeCardItem — skip rather than invent data.
                progress = null,
            )
        }
    }
}

/** One row of the selected-category vertical grid. */
@Composable
private fun HomeCategoryGridRow(
    items: List<HomeCardItem>,
    columns: Int,
    horizontalPadding: Dp,
    cardWidth: Dp,
    onCardClick: (HomeCardItem) -> Unit,
    initialFocusItem: HomeCardItem?,
    initialFocusRequester: FocusRequester,
    isLive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = LayoutDimens.LazyRowFocusPadding),
        horizontalArrangement = Arrangement.spacedBy(LayoutDimens.CardRowSpacing),
    ) {
        items.forEach { cardItem ->
            val cardModifier = if (initialFocusItem != null && cardItem == initialFocusItem) {
                Modifier
                    .width(cardWidth)
                    .focusRequester(initialFocusRequester)
            } else {
                Modifier.width(cardWidth)
            }

            val liveBadge: (@Composable () -> Unit)? = if (isLive) {
                { LiveBadge() }
            } else {
                null
            }

            FocusableCard(
                title = cardItem.title,
                imageUrl = cardItem.imageUrl,
                onClick = { onCardClick(cardItem) },
                modifier = cardModifier,
                badge = liveBadge,
                progress = null,
            )
        }

        repeat((columns - items.size).coerceAtLeast(0)) {
            Spacer(modifier = Modifier.width(cardWidth))
        }
    }
}

/** Small LIVE pill badge rendered in the top-left corner of a live channel card. */
@Composable
private fun LiveBadge() {
    GlassSurface(
        shape = RoundedCornerShape(999.dp),
        strong = true,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color = SemanticLive, shape = CircleShape),
            )
            Text(
                text = "LIVE",
                style = MaterialTheme.typography.labelSmall,
                color = SemanticLive,
            )
        }
    }
}

/**
 * Immersive glass hero banner — Cinematic Glass V2 language.
 *
 * Layout (bottom to top):
 *  1. Full-bleed backdrop image (Coil, ContentScale.Crop)
 *  2. Dual scrim: horizontal (left heavy) + vertical (bottom heavy), matching .hero .scrim
 *  3. Content column: eyebrow label + large title + chips row + actions row
 *
 * Uses [RadiusXl] (28 dp) for the hero shape per styles.css (.hero border-radius: --radius-xl).
 * The top-bar overlays this composable, so no top padding is added here.
 */
@Composable
private fun HomeHero(
    item: HomeCardItem,
    onClick: () -> Unit,
    onDetailClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    val heroShape = RoundedCornerShape(
        bottomStart = RadiusXl,
        bottomEnd = RadiusXl,
        topStart = 0.dp,
        topEnd = 0.dp,
    )

    val placeholderPainter = remember { ColorPainter(BackgroundElevated) }
    val errorPainter = remember { ColorPainter(BackgroundElevated) }

    // Dual scrim matching .hero .scrim in styles.css:
    //   horizontal: rgba(10,10,15,0.92) 0% -> rgba(10,10,15,0.55) 42% -> rgba(10,10,15,0.05) 100%
    //   vertical:   rgba(10,10,15,0.90) 0% -> transparent 55%
    val horizontalScrim = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.00f to BackgroundBase.copy(alpha = 0.92f),
            0.42f to BackgroundBase.copy(alpha = 0.55f),
            1.00f to BackgroundBase.copy(alpha = 0.05f),
        ),
    )
    val verticalScrim = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color.Transparent,
            0.45f to Color.Transparent,
            1.00f to BackgroundBase.copy(alpha = 0.90f),
        ),
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 16:9 by default, but never shorter than a floor that guarantees the
        // bottom-aligned hero content clears the floating top bar above it.
        val heroHeight = maxOf(maxWidth * (9f / 16f), 360.dp)

        Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clip(heroShape),
    ) {
        // 1. Backdrop image
        AsyncImage(
            model = item.imageUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = placeholderPainter,
            error = errorPainter,
            fallback = placeholderPainter,
        )

        // 2. Horizontal scrim (left-heavy)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(horizontalScrim),
        )

        // 3. Vertical scrim (bottom-heavy)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(verticalScrim),
        )

        // 4. Hero content — aligned to bottom-start, max 62% width per styles.css.
        // Top inset reserves the status bar + floating header zone (title row + tab bar,
        // Task 1) so the hero title never overlaps the "Accueil / Recherche / Reglages" +
        // tabs overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(
                    start = Spacing.xxl,
                    end = Spacing.xxl,
                    bottom = Spacing.xxl,
                    top = LayoutDimens.TopBarHeight + LayoutDimens.TabRowHeight,
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.62f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // Eyebrow label — content type
                val eyebrow = when (item.contentType) {
                    ContentType.LIVE -> "En direct"
                    ContentType.MOVIE -> "Film"
                    ContentType.SERIES -> "Serie"
                }
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentSolid,
                )

                // Large title
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Chips row — genre/type metadata chips (derived from contentType since
                // HomeCardItem carries no genre/year/duration fields)
                val chips = when (item.contentType) {
                    ContentType.LIVE -> listOf("En direct", "HD")
                    ContentType.MOVIE -> listOf("Film", "HD")
                    ContentType.SERIES -> listOf("Serie", "HD")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    chips.forEach { chip ->
                        CategoryChip(
                            label = chip,
                            selected = false,
                            onClick = {},
                            enabled = false,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Actions row — primary play, ghost details, icon add-to-list
                // The first PrimaryButton also carries the focusRequester for initial D-pad focus.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hasResume = item.resumeStreamUrl != null
                    PrimaryButton(
                        label = if (hasResume) "Reprendre" else "Lecture",
                        onClick = onClick,
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                    GhostButton(
                        label = "Details",
                        onClick = onDetailClick,
                    )
                    // TODO: wire to VM toggleFavorite when available
                    GlassIconButton(
                        icon = Icons.Default.Add,
                        contentDescription = "Ajouter a ma liste",
                        onClick = {},
                        enabled = false,
                    )
                }
            }
        }
    }
    }
}

// --- Previews ---

private val previewContinueWatchingRow = HomeRow(
    categoryId = "continue-watching",
    title = "Reprendre",
    items = listOf(
        HomeCardItem(
            id = "m1",
            title = "Explosion Totale",
            imageUrl = null,
            contentType = ContentType.MOVIE,
            resumeStreamUrl = "http://example.com:8080/movie/u/p/m1.mp4",
        ),
    ),
)

private val previewMyListRow = HomeRow(
    categoryId = "my-list",
    title = "Ma liste",
    items = listOf(
        HomeCardItem(id = "m1", title = "Explosion Totale", imageUrl = null, contentType = ContentType.MOVIE),
        HomeCardItem(id = "l1", title = "Chaine Sport 1", imageUrl = null, contentType = ContentType.LIVE),
        HomeCardItem(id = "s1", title = "La Casa de Papel", imageUrl = null, contentType = ContentType.SERIES),
    ),
)

private val previewLiveRow = HomeRow(
    categoryId = "1",
    title = "Sport",
    items = listOf(
        HomeCardItem(id = "l1", title = "Chaine Sport 1", imageUrl = null, contentType = ContentType.LIVE),
        HomeCardItem(id = "l2", title = "Chaine Sport 2", imageUrl = null, contentType = ContentType.LIVE),
    ),
)

private val previewMovieRow = HomeRow(
    categoryId = "2",
    title = "Action",
    items = listOf(
        HomeCardItem(id = "m1", title = "Explosion Totale", imageUrl = null, contentType = ContentType.MOVIE),
        HomeCardItem(id = "m2", title = "Vengeance Nocturne", imageUrl = null, contentType = ContentType.MOVIE),
    ),
)

private val previewMovieRowAlt = HomeRow(
    categoryId = "4",
    title = "Comedies",
    items = listOf(
        HomeCardItem(id = "m3", title = "Panique au Bureau", imageUrl = null, contentType = ContentType.MOVIE),
        HomeCardItem(id = "m4", title = "Sprint Final", imageUrl = null, contentType = ContentType.MOVIE),
        HomeCardItem(id = "m5", title = "Bug & Love", imageUrl = null, contentType = ContentType.MOVIE),
    ),
)

private val previewSeriesRow = HomeRow(
    categoryId = "3",
    title = "Drames",
    items = listOf(
        HomeCardItem(id = "s1", title = "La Casa de Papel", imageUrl = null, contentType = ContentType.SERIES),
    ),
)

@Preview(name = "Home content", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun HomeContentPreview() {
    IptvAppTheme {
        HomeContent(
            uiState = HomeUiState(
                continueWatchingRows = listOf(previewContinueWatchingRow),
                myListRows = listOf(previewMyListRow),
                liveRows = listOf(previewLiveRow),
                movieRows = listOf(previewMovieRow),
                seriesRows = listOf(previewSeriesRow),
                isLoading = false,
                errorMessage = null,
                liveLanguages = listOf("FR", "EN"),
                movieLanguages = listOf("FR", "VOSTFR"),
                seriesLanguages = listOf("FR", "EN"),
                selectedLiveLanguage = null,
                selectedMovieLanguage = "FR",
                selectedSeriesLanguage = null,
            ),
            onCardClick = {},
            onNavigateToSearch = {},
            onNavigateToSettings = {},
            onRetry = {},
            onNavigateToDetail = { _, _ -> },
        )
    }
}

@Preview(name = "Home loading", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun HomeContentLoadingPreview() {
    IptvAppTheme {
        HomeContent(
            uiState = HomeUiState(isLoading = true),
            onCardClick = {},
            onNavigateToSearch = {},
            onNavigateToSettings = {},
            onRetry = {},
            onNavigateToDetail = { _, _ -> },
        )
    }
}

@Preview(name = "Home error", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun HomeContentErrorPreview() {
    IptvAppTheme {
        HomeContent(
            uiState = HomeUiState(isLoading = false, errorMessage = "Connexion au serveur impossible."),
            onCardClick = {},
            onNavigateToSearch = {},
            onNavigateToSettings = {},
            onRetry = {},
            onNavigateToDetail = { _, _ -> },
        )
    }
}

@Preview(
    name = "Home catalog selection (phone)",
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
    widthDp = 412,
    heightDp = 900,
)
@Composable
private fun HomeCatalogSelectionPhonePreview() {
    IptvAppTheme {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundBase),
        ) {
            homeLanguageFilterRow(
                key = "preview-language-filter",
                languages = listOf("FR", "EN", "VOSTFR"),
                selected = "FR",
                horizontalPadding = LayoutDimens.ContentPaddingPhone,
                onLanguageSelected = {},
            )
            homeCategorySelectorRow(
                key = "preview-category-selector",
                rows = listOf(previewMovieRow, previewMovieRowAlt),
                selectedCategoryId = previewMovieRowAlt.categoryId,
                horizontalPadding = LayoutDimens.ContentPaddingPhone,
                onCategorySelected = {},
            )
            homeCategoryGridSection(
                sectionTitle = previewMovieRowAlt.title,
                row = previewMovieRowAlt,
                columns = 2,
                horizontalPadding = LayoutDimens.ContentPaddingPhone,
                cardWidth = CardDimens.PosterWidthPhone,
                onCardClick = {},
                initialFocusItem = previewMovieRowAlt.items.first(),
                initialFocusRequester = FocusRequester(),
            )
        }
    }
}

@Preview(
    name = "Home catalog selection (TV)",
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
    widthDp = 1280,
    heightDp = 720,
)
@Composable
private fun HomeCatalogSelectionTvPreview() {
    IptvAppTheme {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundBase),
        ) {
            homeLanguageFilterRow(
                key = "preview-tv-language-filter",
                languages = listOf("FR", "EN", "VOSTFR"),
                selected = "FR",
                horizontalPadding = LayoutDimens.ContentPaddingTv,
                onLanguageSelected = {},
            )
            homeCategorySelectorRow(
                key = "preview-tv-category-selector",
                rows = listOf(previewMovieRow, previewMovieRowAlt),
                selectedCategoryId = previewMovieRowAlt.categoryId,
                horizontalPadding = LayoutDimens.ContentPaddingTv,
                onCategorySelected = {},
            )
            homeCategoryGridSection(
                sectionTitle = previewMovieRowAlt.title,
                row = previewMovieRowAlt,
                columns = 4,
                horizontalPadding = LayoutDimens.ContentPaddingTv,
                cardWidth = CardDimens.PosterWidthTv,
                onCardClick = {},
                initialFocusItem = previewMovieRowAlt.items.first(),
                initialFocusRequester = FocusRequester(),
            )
        }
    }
}

@Preview(name = "Home empty", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun HomeContentEmptyPreview() {
    IptvAppTheme {
        HomeContent(
            uiState = HomeUiState(isLoading = false, errorMessage = null),
            onCardClick = {},
            onNavigateToSearch = {},
            onNavigateToSettings = {},
            onRetry = {},
            onNavigateToDetail = { _, _ -> },
        )
    }
}
