package com.bobot.iptvapp.ui.screen.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.Spacing
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented geometry regression test for the Home catalog header-overlap bug.
 *
 * Root cause: [HomeRowsContent] used to reserve a hard-coded
 * `LayoutDimens.TopBarHeight + LayoutDimens.TabRowHeight` (104dp) of top clearance for non-hero
 * catalog tabs (Chaines / Films / Series), while the floating [HomeHeader] it clears actually
 * renders taller than that on a real device — leaving the top of the first language chip row
 * covered by the opaque header.
 *
 * This test renders [HomeContent] on a real device (connected serial R3GL600FNBT), switches to
 * each catalog tab in turn, and asserts the language filter row's top edge clears the header's
 * actual measured bottom edge by *at least* [Spacing.sm] (8dp, converted to px via the real
 * device density) — the exact clearance [HomeRowsContent] is supposed to reserve. A plain
 * non-overlap check would pass even if the fix regressed to a 1px gap, so the assertion is on the
 * measured gap in px, not merely `top >= bottom`.
 *
 * All three catalog tabs go through the same `heroItem == null` branch in [HomeRowsContent] (see
 * its `headerClearance` computation), so this single test looping over the three tabs exercises
 * that shared code path once per tab rather than needing three near-identical test methods.
 *
 * ## Running
 * ```
 * adb.exe shell am instrument -w \
 *   -e class com.bobot.iptvapp.ui.screen.home.HomeHeaderClearanceTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class HomeHeaderClearanceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val liveRow = HomeRow(
        categoryId = "live-1",
        title = "Sport",
        items = listOf(HomeCardItem(id = "c1", title = "Chaine 1", imageUrl = null, contentType = ContentType.LIVE)),
    )
    private val movieRow = HomeRow(
        categoryId = "movie-1",
        title = "Action",
        items = listOf(HomeCardItem(id = "m1", title = "Film 1", imageUrl = null, contentType = ContentType.MOVIE)),
    )
    private val seriesRow = HomeRow(
        categoryId = "series-1",
        title = "Drames",
        items = listOf(HomeCardItem(id = "s1", title = "Serie 1", imageUrl = null, contentType = ContentType.SERIES)),
    )

    /** Tab label to click, paired with the `key` its [homeLanguageFilterRow] was tagged with. */
    private val tabsUnderTest = listOf(
        "Chaines" to "language-filter-live",
        "Films" to "language-filter-movies",
        "Series" to "language-filter-series",
    )

    @Test
    fun languageFilterRow_clearsHomeHeaderBySpacingSm_onEveryCatalogTab() {
        composeTestRule.setContent {
            IptvAppTheme {
                HomeContent(
                    uiState = HomeUiState(
                        liveRows = listOf(liveRow),
                        movieRows = listOf(movieRow),
                        seriesRows = listOf(seriesRow),
                        isLoading = false,
                        liveLanguages = listOf("FR", "EN"),
                        movieLanguages = listOf("FR", "VOSTFR"),
                        seriesLanguages = listOf("FR", "EN"),
                    ),
                    onCardClick = {},
                    onNavigateToDetail = { _, _ -> },
                    onNavigateToSearch = {},
                    onNavigateToSettings = {},
                    onRetry = {},
                )
            }
        }

        // dp -> px at the real device's density, matching how the production code (headerClearance
        // in HomeRowsContent) converts Spacing.sm — not a hardcoded pixel guess.
        val displayMetrics = composeTestRule.activity.resources.displayMetrics
        val density = Density(displayMetrics.density)
        val expectedGapPx = with(density) { Spacing.sm.toPx() }
        // Sub-pixel rounding from Dp <-> px conversions on the way in (measured header height)
        // and back out (padding) — not a tolerance for the bug itself.
        val roundingTolerancePx = 1f

        for ((tabLabel, chipRowTag) in tabsUnderTest) {
            composeTestRule.onNodeWithText(tabLabel).performClick()
            composeTestRule.waitForIdle()

            val headerBounds = composeTestRule.onNodeWithTag(HOME_HEADER_TEST_TAG).fetchSemanticsNode().boundsInRoot
            val chipRowBounds = composeTestRule.onNodeWithTag(chipRowTag).fetchSemanticsNode().boundsInRoot
            val actualGapPx = chipRowBounds.top - headerBounds.bottom

            assertTrue(
                "[$tabLabel] language filter row must clear HomeHeader by at least Spacing.sm " +
                    "(${expectedGapPx}px at this device's density) — header bottom=" +
                    "${headerBounds.bottom}, chip row top=${chipRowBounds.top}, actual gap=" +
                    "${actualGapPx}px. See HomeRowsContent's headerClearance.",
                actualGapPx >= expectedGapPx - roundingTolerancePx,
            )
        }
    }

    /**
     * Physical-device follow-up regression: on the Accueil tab, [HomeHero] used to start at
     * `y = 0` full-bleed *behind* the floating [HomeHeader] (the whole point of the hero being
     * immersive) — but the 75%-opaque-floor [HomeTabBar] (see [HomeHeader]'s `containerColor`)
     * then draws a dark horizontal band straight across the top of the hero image. The user
     * rejected that overlap outright: the hero must begin below [HomeHeader] with at least
     * [Spacing.sm] of clearance, exactly like the catalog tabs above. Reported on a physical
     * Samsung SM-S948B, Android 16, portrait — see `iptv-home-hero-bug.png`.
     */
    @Test
    fun heroContent_clearsHomeHeaderBySpacingSm_onHomeTab() {
        composeTestRule.setContent {
            IptvAppTheme {
                HomeContent(
                    uiState = HomeUiState(
                        liveRows = listOf(liveRow),
                        movieRows = listOf(movieRow),
                        seriesRows = listOf(seriesRow),
                        isLoading = false,
                    ),
                    onCardClick = {},
                    onNavigateToDetail = { _, _ -> },
                    onNavigateToSearch = {},
                    onNavigateToSettings = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // selectedTab is rememberSaveable (QA finding Y1) — force Accueil explicitly rather than
        // relying on the initial default, so this test is deterministic regardless of any restored
        // instance/saved state (e.g. from another test's SavedStateRegistry in the same run). A
        // catalog tab has no hero at all, which would fail this test for the wrong reason (missing
        // node) instead of the geometry bug it targets. "Accueil" also labels the (non-clickable)
        // HomeTopBar title while Home is selected, so the Tab node needs a clickable filter to
        // disambiguate from it.
        composeTestRule.onNode(hasText("Accueil") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()

        // dp -> px at the real device's density, matching how the production code (headerClearance
        // in HomeRowsContent) converts Spacing.sm — not a hardcoded pixel guess.
        val displayMetrics = composeTestRule.activity.resources.displayMetrics
        val density = Density(displayMetrics.density)
        val expectedGapPx = with(density) { Spacing.sm.toPx() }
        // Sub-pixel rounding from Dp <-> px conversions on the way in (measured header height)
        // and back out (padding) — not a tolerance for the bug itself.
        val roundingTolerancePx = 1f

        val headerBounds = composeTestRule.onNodeWithTag(HOME_HEADER_TEST_TAG).fetchSemanticsNode().boundsInRoot
        // Unmerged tree — the hero's PrimaryButton (initial D-pad focus target) is a semantics
        // merge boundary and otherwise absorbs the plain testTag() Box's node into itself.
        val heroBounds = composeTestRule.onNodeWithTag(HOME_HERO_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val actualGapPx = heroBounds.top - headerBounds.bottom

        assertTrue(
            "Home hero must clear HomeHeader by at least Spacing.sm " +
                "(${expectedGapPx}px at this device's density) — header bottom=" +
                "${headerBounds.bottom}, hero top=${heroBounds.top}, actual gap=" +
                "${actualGapPx}px. See HomeRowsContent's headerClearance.",
            actualGapPx >= expectedGapPx - roundingTolerancePx,
        )
    }
}
