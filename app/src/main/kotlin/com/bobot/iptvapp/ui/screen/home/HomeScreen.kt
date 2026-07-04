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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
        modifier = modifier,
    )
}

/** Maps the domain [ContentType] to the string contract expected by the Detail route. */
private fun ContentType.toDetailContentType(): String = when (this) {
    ContentType.LIVE -> "live"
    ContentType.MOVIE -> "movie"
    ContentType.SERIES -> "series"
}

/**
 * Stateless content — separated from [HomeScreen] so it can be exercised directly in
 * @Preview without a Hilt ViewModel.
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
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase),
    ) {
        when {
            uiState.isLoading && !uiState.hasAnyRows -> HomeLoadingState()

            uiState.errorMessage != null && !uiState.hasAnyRows -> HomeErrorState(
                message = uiState.errorMessage,
                onRetry = onRetry,
            )

            !uiState.hasAnyRows -> HomeEmptyState()

            else -> HomeRowsContent(
                uiState = uiState,
                onCardClick = onCardClick,
                onNavigateToDetail = onNavigateToDetail,
                onRetry = onRetry,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToSettings = onNavigateToSettings,
            )
        }
    }
}

// --- Top bar ---

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
 * Renders the optional hero banner ([HomeHero]) followed by the category rows, inside a
 * single [LazyColumn]. The top-bar floats as an overlay above the hero so the hero image
 * extends edge-to-edge behind it.
 */
@Composable
private fun HomeRowsContent(
    uiState: HomeUiState,
    onCardClick: (HomeCardItem) -> Unit,
    onNavigateToDetail: (contentType: String, contentId: String) -> Unit,
    onRetry: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val isTv = rememberIsTvDevice()
    val horizontalPadding = if (isTv) LayoutDimens.ContentPaddingTv else LayoutDimens.ContentPaddingPhone
    val cardWidth = if (isTv) CardDimens.PosterWidthTv else CardDimens.PosterWidthPhone

    val heroItem = uiState.movieRows.firstOrNull()?.items?.firstOrNull()
        ?: uiState.seriesRows.firstOrNull()?.items?.firstOrNull()
        ?: uiState.liveRows.firstOrNull()?.items?.firstOrNull()

    val firstRowItem = uiState.continueWatchingRows.firstOrNull()?.items?.firstOrNull()
        ?: uiState.myListRows.firstOrNull()?.items?.firstOrNull()
        ?: uiState.liveRows.firstOrNull()?.items?.firstOrNull()
        ?: uiState.movieRows.firstOrNull()?.items?.firstOrNull()
        ?: uiState.seriesRows.firstOrNull()?.items?.firstOrNull()

    val initialFocusRequester = remember { FocusRequester() }
    val rowsFocusTarget: HomeCardItem? = if (heroItem == null) firstRowItem else null

    // Scroll-driven top-bar background: transparent scrim while the first item (hero)
    // is at the top, fading to a fully solid bar once the user scrolls past it.
    val listState = rememberLazyListState()
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
        uiState.continueWatchingRows,
        uiState.myListRows,
        uiState.liveRows,
        uiState.movieRows,
        uiState.seriesRows,
    ) {
        runCatching { initialFocusRequester.requestFocus() }
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
                // Reserve space for the top bar when there is no hero
                item(key = "topbar-spacer") {
                    Spacer(
                        modifier = Modifier
                            .statusBarsPadding()
                            .height(Spacing.xxl),
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

            homeSection(
                sectionTitle = "En direct",
                rows = uiState.liveRows,
                horizontalPadding = horizontalPadding,
                cardWidth = cardWidth,
                onCardClick = onCardClick,
                initialFocusItem = rowsFocusTarget,
                initialFocusRequester = initialFocusRequester,
                isLive = true,
            )

            homeSection(
                sectionTitle = "Films",
                rows = uiState.movieRows,
                horizontalPadding = horizontalPadding,
                cardWidth = cardWidth,
                onCardClick = onCardClick,
                initialFocusItem = rowsFocusTarget,
                initialFocusRequester = initialFocusRequester,
            )

            homeSection(
                sectionTitle = "Series",
                rows = uiState.seriesRows,
                horizontalPadding = horizontalPadding,
                cardWidth = cardWidth,
                onCardClick = onCardClick,
                initialFocusItem = rowsFocusTarget,
                initialFocusRequester = initialFocusRequester,
            )
        }

        // Top bar floats above the LazyColumn so the hero image extends full-bleed underneath
        HomeTopBar(
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
        // Top inset reserves the status bar + floating top-bar zone so the hero
        // title never overlaps the "Accueil / Recherche / Reglages" overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(
                    start = Spacing.xxl,
                    end = Spacing.xxl,
                    bottom = Spacing.xxl,
                    top = LayoutDimens.TopBarHeight,
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
