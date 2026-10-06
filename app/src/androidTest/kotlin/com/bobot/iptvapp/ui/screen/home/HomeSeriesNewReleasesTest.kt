package com.bobot.iptvapp.ui.screen.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.util.MovieSortMode
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests of the Series tab's "Nouveautés" view in [HomeContent]: default selection, its
 * single-order label, waiting/empty/failed states, paging through
 * [HomeViewModel.onLoadMoreSeriesNewReleases], D-pad focus, and the selection surviving a trip to a
 * series' detail screen. Same approach as [HomeMovieSortTest]: hand-built [HomeUiState]s.
 *
 * ## Running
 * Android TV emulator only (not a phone):
 * ```
 * adb.exe -s emulator-5554 shell am instrument -w \
 *   -e class com.bobot.iptvapp.ui.screen.home.HomeSeriesNewReleasesTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeSeriesNewReleasesTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun card(id: String, title: String) =
        HomeCardItem(id = id, title = title, imageUrl = null, contentType = ContentType.SERIES)

    private fun newReleaseCards(count: Int, prefix: String = "Serie neuve") =
        (1..count).map { card("n$it", "$prefix $it") }

    private val dramaRow = HomeRow(categoryId = "60", title = "Drames", items = listOf(card("d1", "Serie Drame")))
    private val comedyRow = HomeRow(categoryId = "61", title = "Comedies", items = listOf(card("c1", "Serie Comedie")))

    private fun seriesState(
        newReleaseItems: List<HomeCardItem> = newReleaseCards(3),
        hasMore: Boolean = false,
        seriesLoadState: CatalogTabLoadState = CatalogTabLoadState.LOADED,
        seriesRows: List<HomeRow> = listOf(dramaRow, comedyRow),
        selectedLanguage: String? = null,
        publishedLanguage: String? = selectedLanguage,
        isFailed: Boolean = false,
        errorMessage: String? = null,
    ) = HomeUiState(
        seriesRows = seriesRows,
        isLoading = false,
        errorMessage = errorMessage,
        seriesLanguages = listOf("EN", "FR"),
        selectedSeriesLanguage = selectedLanguage,
        catalogTabLoadStates = mapOf(ContentType.SERIES to seriesLoadState),
        seriesNewReleases = NewReleasesState(
            language = publishedLanguage,
            items = newReleaseItems,
            hasMore = hasMore,
            isFailed = isFailed,
        ),
    )

    private var uiState by mutableStateOf(HomeUiState())
    private var seriesLoadMoreCalls = 0
    private var movieLoadMoreCalls = 0
    private val pickedLanguages = mutableListOf<Pair<ContentType, String?>>()
    private val openedCards = mutableListOf<HomeCardItem>()

    private fun setContent(initial: HomeUiState, restorationTester: StateRestorationTester? = null) {
        uiState = initial
        val content = @Composable {
            IptvAppTheme {
                HomeContent(
                    uiState = uiState,
                    onCardClick = { openedCards += it },
                    onNavigateToDetail = { _, _ -> },
                    onNavigateToSearch = {},
                    onNavigateToSettings = {},
                    onRetry = {},
                    onLanguageSelected = { type, language -> pickedLanguages += type to language },
                    onLoadMoreNewReleases = { movieLoadMoreCalls++ },
                    onLoadMoreSeriesNewReleases = { seriesLoadMoreCalls++ },
                )
            }
        }
        if (restorationTester != null) restorationTester.setContent(content) else composeTestRule.setContent(content)
        composeTestRule.waitForIdle()
    }

    private fun openSeriesTab() {
        composeTestRule.onNode(hasText("Series") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()
    }

    private fun categoryChip(label: String) =
        composeTestRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("category-selector-series")))

    private fun languageChip(label: String) =
        composeTestRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("language-filter-series")))

    private fun rowsList() = composeTestRule.onNodeWithTag(HOME_ROWS_LIST_TEST_TAG)

    /** See [HomeMovieSortTest.useDpad]. */
    private fun useDpad() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        composeTestRule.waitForIdle()
    }

    @Before
    @After
    fun useTouch() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    private fun pressKeyOnFocused(key: Key) {
        composeTestRule.onNode(isFocused()).performKeyInput { pressKey(key) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun nouveautes_isSelectedByDefault_aheadOfTheCategories_withTheReleaseOrderLabel() {
        setContent(seriesState())
        openSeriesTab()

        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
        categoryChip("Drames").assertIsNotSelected()
        val chipsLeft = listOf(NEW_RELEASES_LABEL, "Drames", "Comedies").map {
            categoryChip(it).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals("Nouveautés first, categories in their order", chipsLeft.sorted(), chipsLeft)

        composeTestRule.onNodeWithTag(SERIES_SORT_CONTROL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Trier par : ${MovieSortMode.RECENT_RELEASE.label}").assertIsDisplayed()
        // No second order for series without a product decision.
        composeTestRule.onNodeWithText(MovieSortMode.RECENTLY_ADDED.label).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("Serie neuve 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Serie Drame").assertDoesNotExist()
    }

    @Test
    fun aCategory_hidesTheOrderLabel_andNouveautesBringsItBack() {
        setContent(seriesState())
        openSeriesTab()

        categoryChip("Drames").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SERIES_SORT_CONTROL_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("Serie Drame").assertIsDisplayed()

        categoryChip(NEW_RELEASES_LABEL).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SERIES_SORT_CONTROL_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Serie neuve 1").assertIsDisplayed()
    }

    @Test
    fun aSeriesCard_opensThroughTheCardCallback() {
        setContent(seriesState())
        openSeriesTab()

        composeTestRule.onNodeWithText("Serie neuve 2").performClick()

        assertEquals(listOf(card("n2", "Serie neuve 2")), openedCards)
    }

    @Test
    fun anEmptyPageWhileLoading_showsProgress_notTheEmptyState() {
        setContent(seriesState(newReleaseItems = emptyList(), seriesLoadState = CatalogTabLoadState.LOADING))
        openSeriesTab()

        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
        composeTestRule.onNodeWithText("Chargement des nouveautés…").assertExists()
        composeTestRule.onNodeWithText("Catalogue en cours de chargement").assertExists()
        composeTestRule.onNodeWithText(SERIES_NEW_RELEASES_EMPTY_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText("Aucun contenu disponible pour le moment.").assertDoesNotExist()
    }

    @Test
    fun aNewLanguage_hidesTheOldCards_untilTheyAreMerged() {
        setContent(seriesState(selectedLanguage = "FR"))
        openSeriesTab()
        composeTestRule.onNodeWithText("Serie neuve 1").assertIsDisplayed()

        // HomeViewModel publishes the language first; the cards are still the FR ones.
        uiState = seriesState(selectedLanguage = "EN", publishedLanguage = "FR")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Serie neuve 1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Tri en cours…").assertIsDisplayed()

        uiState = seriesState(selectedLanguage = "EN", newReleaseItems = newReleaseCards(2, prefix = "English"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("English 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tri en cours…").assertDoesNotExist()
    }

    @Test
    fun noSeriesOnceLoaded_showsATerminalEmptyState_andKeepsTheLanguageChips() {
        setContent(seriesState(newReleaseItems = emptyList(), seriesRows = emptyList(), selectedLanguage = "FR"))
        openSeriesTab()

        composeTestRule.onNodeWithText(SERIES_NEW_RELEASES_EMPTY_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText("Chargement des nouveautés…").assertDoesNotExist()
        composeTestRule.onNodeWithText("Aucun contenu disponible pour le moment.").assertDoesNotExist()
        languageChip("Toutes").performClick()
        assertEquals(listOf(ContentType.SERIES to null), pickedLanguages)
    }

    @Test
    fun aFailedReload_settlesOnTheEmptyState_withTheErrorBanner() {
        setContent(
            seriesState(newReleaseItems = emptyList(), isFailed = true, errorMessage = "Serveur injoignable"),
        )
        openSeriesTab()

        composeTestRule.onNodeWithText("Serveur injoignable").assertExists()
        composeTestRule.onNodeWithText(SERIES_NEW_RELEASES_EMPTY_LABEL).assertExists()
        composeTestRule.onNodeWithText("Chargement des nouveautés…").assertDoesNotExist()
        categoryChip("Drames").assertExists()
    }

    @Test
    fun theNextPage_isAskedOfTheSeriesView_nearTheBottom_andOnlyWhileThereIsMore() {
        setContent(seriesState(newReleaseItems = newReleaseCards(60), hasMore = true))
        openSeriesTab()
        assertEquals("the first page must not preload the next one", 0, seriesLoadMoreCalls)

        rowsList().performScrollToNode(hasText("Serie neuve 60"))
        composeTestRule.waitForIdle()
        assertEquals(1, seriesLoadMoreCalls)
        assertEquals("never the Films view", 0, movieLoadMoreCalls)

        uiState = seriesState(newReleaseItems = newReleaseCards(80), hasMore = false)
        composeTestRule.waitForIdle()
        rowsList().performScrollToNode(hasText("Serie neuve 80"))
        composeTestRule.waitForIdle()
        assertEquals(1, seriesLoadMoreCalls)
    }

    @Test
    fun dpad_goesFromTheFirstCard_upToTheChips_andBack() {
        setContent(seriesState(newReleaseItems = emptyList(), seriesLoadState = CatalogTabLoadState.LOADING))
        openSeriesTab()
        useDpad()

        // The first card takes focus as soon as the first page lands.
        uiState = seriesState(newReleaseItems = newReleaseCards(8))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Serie neuve 1").assertIsFocused()

        // The order label is not a focus stop: Up goes straight to the category chips.
        pressKeyOnFocused(Key.DirectionUp)
        composeTestRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("category-selector-series"))).assertExists()

        pressKeyOnFocused(Key.DirectionDown)
        composeTestRule.onNode(isFocused() and hasText("Serie neuve", substring = true)).assertExists()
    }

    @Test
    fun focus_staysOnTheChosenChip_whileCategoriesAndNouveautesKeepLoading() {
        setContent(seriesState(newReleaseItems = emptyList(), seriesLoadState = CatalogTabLoadState.LOADING))
        openSeriesTab()
        useDpad()
        categoryChip("Comedies").performSemanticsAction(SemanticsActions.RequestFocus)
        categoryChip("Comedies").assertIsFocused()

        val kids = HomeRow(categoryId = "63", title = "Kids", items = listOf(card("k1", "Serie Kids")))
        uiState = seriesState(
            newReleaseItems = newReleaseCards(6, prefix = "Arrivee"),
            seriesLoadState = CatalogTabLoadState.LOADING,
            seriesRows = listOf(dramaRow, comedyRow, kids),
        )
        composeTestRule.waitForIdle()

        categoryChip("Comedies").assertIsFocused()
        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
    }

    @Test
    fun nouveautes_survivesATripToASeriesDetail() {
        val restorationTester = StateRestorationTester(composeTestRule)
        setContent(seriesState(selectedLanguage = "FR"), restorationTester)
        openSeriesTab()

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Series") and hasClickAction()).assertIsSelected()
        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
        composeTestRule.onNodeWithTag(SERIES_SORT_CONTROL_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Serie neuve 1").assertIsDisplayed()
    }

    @Test
    fun theChosenCategory_survivesATripToASeriesDetail() {
        val restorationTester = StateRestorationTester(composeTestRule)
        setContent(seriesState(selectedLanguage = "FR"), restorationTester)
        openSeriesTab()
        categoryChip("Comedies").performClick()
        composeTestRule.waitForIdle()

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        // Same language as before the trip: not a change, so the category is kept.
        categoryChip("Comedies").assertIsSelected()
        composeTestRule.onNodeWithText("Serie Comedie").assertIsDisplayed()
    }
}
