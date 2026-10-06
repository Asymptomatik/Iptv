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
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests of the Films tab's "Nouveautés" view in [HomeContent]: default selection,
 * the order control, paging through [HomeViewModel.onLoadMoreNewReleases], D-pad focus, and the
 * selection surviving a trip to a film's detail screen.
 *
 * [HomeContent] is driven with hand-built [HomeUiState]s, so the tests play [HomeViewModel]'s part
 * themselves (e.g. publishing the picked order first, the re-sorted cards later).
 *
 * ## Running
 * Android TV emulator only (not a phone):
 * ```
 * adb.exe -s emulator-5554 shell am instrument -w \
 *   -e class com.bobot.iptvapp.ui.screen.home.HomeMovieSortTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeMovieSortTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun card(id: String, title: String) =
        HomeCardItem(id = id, title = title, imageUrl = null, contentType = ContentType.MOVIE)

    private fun newReleaseCards(count: Int, prefix: String = "Nouveau") =
        (1..count).map { card("n$it", "$prefix $it") }

    private val actionRow = HomeRow(categoryId = "40", title = "Action", items = listOf(card("a1", "Film Action")))
    private val dramaRow = HomeRow(categoryId = "41", title = "Drame", items = listOf(card("d1", "Film Drame")))

    private fun filmsState(
        newReleaseItems: List<HomeCardItem> = newReleaseCards(3),
        hasMore: Boolean = false,
        movieLoadState: CatalogTabLoadState = CatalogTabLoadState.LOADED,
        movieRows: List<HomeRow> = listOf(actionRow, dramaRow),
    ) = HomeUiState(
        movieRows = movieRows,
        seriesRows = listOf(HomeRow(categoryId = "s1", title = "Drames", items = listOf(card("s1", "Serie 1")))),
        isLoading = false,
        catalogTabLoadStates = mapOf(ContentType.MOVIE to movieLoadState, ContentType.SERIES to CatalogTabLoadState.LOADED),
        newReleases = NewReleasesState(items = newReleaseItems, hasMore = hasMore),
    )

    private var uiState by mutableStateOf(HomeUiState())
    private val pickedModes = mutableListOf<MovieSortMode>()
    private var loadMoreCalls = 0
    private val pickedLanguages = mutableListOf<Pair<ContentType, String?>>()

    private fun setContent(initial: HomeUiState, restorationTester: StateRestorationTester? = null) {
        uiState = initial
        val content = @Composable {
            IptvAppTheme {
                HomeContent(
                    uiState = uiState,
                    onCardClick = {},
                    onNavigateToDetail = { _, _ -> },
                    onNavigateToSearch = {},
                    onNavigateToSettings = {},
                    onRetry = {},
                    onLanguageSelected = { type, language -> pickedLanguages += type to language },
                    onMovieSortModeSelected = { pickedModes += it },
                    onLoadMoreNewReleases = { loadMoreCalls++ },
                )
            }
        }
        if (restorationTester != null) restorationTester.setContent(content) else composeTestRule.setContent(content)
        composeTestRule.waitForIdle()
    }

    private fun openTab(label: String) {
        composeTestRule.onNode(hasText(label) and hasClickAction()).performClick()
        composeTestRule.waitForIdle()
    }

    private fun categoryChip(label: String) =
        composeTestRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("category-selector-movies")))

    private fun sortChip(mode: MovieSortMode) =
        composeTestRule.onNode(hasText(mode.label) and hasAnyAncestor(hasTestTag(MOVIE_SORT_CONTROL_TEST_TAG)))

    private fun rowsList() = composeTestRule.onNodeWithTag(HOME_ROWS_LIST_TEST_TAG)

    /**
     * Leaves touch mode, as a remote does: in touch mode nothing clickable takes focus — which a TV,
     * driven by D-pad only, never is.
     */
    private fun useDpad() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        composeTestRule.waitForIdle()
    }

    /**
     * Touch mode is device-wide and outlives a test, and Compose's injected clicks never switch it
     * back: without this, a test after [useDpad] would start with focus — and the list scrolled to
     * the focused card — and tap chips that sit under the header.
     */
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
    fun nouveautes_isSelectedByDefault_aheadOfTheCategories_withItsOrderControl() {
        setContent(filmsState())
        openTab("Films")

        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
        categoryChip("Action").assertIsNotSelected()
        val chipsLeft = listOf(NEW_RELEASES_LABEL, "Action", "Drame").map {
            categoryChip(it).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals("Nouveautés first, categories in their order", chipsLeft.sorted(), chipsLeft)

        sortChip(MovieSortMode.RECENT_RELEASE).assertIsSelected()
        sortChip(MovieSortMode.RECENTLY_ADDED).assertIsNotSelected()
        composeTestRule.onNodeWithText("Nouveau 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Film Action").assertDoesNotExist()
    }

    @Test
    fun theOrderControl_isOnlyShownOnNouveautes_andNeverOnOtherTabs() {
        setContent(filmsState())
        openTab("Films")

        categoryChip("Drame").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("Film Drame").assertIsDisplayed()

        categoryChip(NEW_RELEASES_LABEL).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertExists()

        openTab("Series")
        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertDoesNotExist()
        // Series has its own "Nouveautés" chip and order label (series-date-sort), never the Films
        // control — the only Nouveautés chip on screen is under the Series category selector.
        composeTestRule
            .onNode(hasText(NEW_RELEASES_LABEL) and hasClickAction() and hasAnyAncestor(hasTestTag("category-selector-series")))
            .assertExists()
        categoryChip(NEW_RELEASES_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithTag(SERIES_SORT_CONTROL_TEST_TAG).assertExists()
    }

    @Test
    fun pickingAnOrder_reportsIt_andHidesTheOldCardsUntilTheyAreReSorted() {
        setContent(filmsState())
        openTab("Films")

        sortChip(MovieSortMode.RECENTLY_ADDED).performClick()
        assertEquals(listOf(MovieSortMode.RECENTLY_ADDED), pickedModes)

        // HomeViewModel publishes the choice first; the cards are still in release order.
        uiState = uiState.copy(selectedMovieSortMode = MovieSortMode.RECENTLY_ADDED)
        composeTestRule.waitForIdle()
        sortChip(MovieSortMode.RECENTLY_ADDED).assertIsSelected()
        composeTestRule.onNodeWithText("Nouveau 1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Tri en cours…").assertIsDisplayed()

        uiState = uiState.copy(
            newReleases = NewReleasesState(
                sortMode = MovieSortMode.RECENTLY_ADDED,
                items = newReleaseCards(3, prefix = "Ajouté"),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Ajouté 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tri en cours…").assertDoesNotExist()
    }

    @Test
    fun anEmptyPageWhileLoading_showsProgress_notTheEmptyState() {
        setContent(filmsState(newReleaseItems = emptyList(), movieLoadState = CatalogTabLoadState.LOADING))
        openTab("Films")

        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
        composeTestRule.onNodeWithText("Chargement des nouveautés…").assertExists()
        composeTestRule.onNodeWithText("Catalogue en cours de chargement").assertExists()
        composeTestRule.onNodeWithText("Aucun contenu disponible pour le moment.").assertDoesNotExist()
    }

    /**
     * FR picked while only EN categories hold films: the view model publishes no Films row at all.
     * [HomeContent] must still offer the language chips and "Nouveautés", or the user is stuck.
     */
    private fun noFilmsForFrState(
        movieLoadState: CatalogTabLoadState = CatalogTabLoadState.LOADED,
        newReleasesLanguage: String? = "FR",
        errorMessage: String? = null,
    ) = filmsState(newReleaseItems = emptyList(), movieLoadState = movieLoadState, movieRows = emptyList()).copy(
        movieLanguages = listOf("EN", "FR"),
        selectedMovieLanguage = "FR",
        newReleases = NewReleasesState(language = newReleasesLanguage),
        errorMessage = errorMessage,
    )

    private fun languageChip(label: String) =
        composeTestRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("language-filter-movies")))

    private fun assertFilmsControlsShown() {
        languageChip("FR").assertIsSelected()
        languageChip("Toutes").assertIsDisplayed()
        languageChip("EN").assertIsDisplayed()
        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Aucun contenu disponible pour le moment.").assertDoesNotExist()
    }

    @Test
    fun aLanguageWithoutFilms_keepsTheFilmsControls_soTheUserCanGoBackToToutesOrEn() {
        setContent(noFilmsForFrState())
        openTab("Films")

        assertFilmsControlsShown()
        languageChip("Toutes").performClick()
        languageChip("EN").performClick()
        assertEquals(listOf(ContentType.MOVIE to null, ContentType.MOVIE to "EN"), pickedLanguages)
    }

    @Test
    fun aLanguageWithoutFilms_keepsTheFilmsControls_whileLoading_andOnError() {
        setContent(noFilmsForFrState(movieLoadState = CatalogTabLoadState.LOADING))
        openTab("Films")
        assertFilmsControlsShown()

        uiState = noFilmsForFrState(errorMessage = "Serveur injoignable")
        composeTestRule.waitForIdle()
        assertFilmsControlsShown()
        composeTestRule.onNodeWithText("Serveur injoignable").assertExists()
    }

    @Test
    fun noFilmsOnceLoaded_showsATerminalEmptyState_notAPermanentSpinner() {
        setContent(noFilmsForFrState())
        openTab("Films")

        composeTestRule.onNodeWithText(NEW_RELEASES_EMPTY_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText("Chargement des nouveautés…").assertDoesNotExist()
        composeTestRule.onNodeWithText("Tri en cours…").assertDoesNotExist()
    }

    @Test
    fun noFilmsYet_keepsTheSpinner_whileLoadingOrReSorting() {
        setContent(noFilmsForFrState(movieLoadState = CatalogTabLoadState.LOADING))
        openTab("Films")
        composeTestRule.onNodeWithText("Chargement des nouveautés…").assertExists()
        composeTestRule.onNodeWithText(NEW_RELEASES_EMPTY_LABEL).assertDoesNotExist()

        // Loaded, but the page for FR is not computed yet (still the EN one): a real wait.
        uiState = noFilmsForFrState(newReleasesLanguage = "EN")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Tri en cours…").assertExists()
        composeTestRule.onNodeWithText(NEW_RELEASES_EMPTY_LABEL).assertDoesNotExist()
    }

    @Test
    fun theNextPage_isOnlyAskedFor_nearTheBottom_andOnlyWhileThereIsMore() {
        setContent(filmsState(newReleaseItems = newReleaseCards(60), hasMore = true))
        openTab("Films")
        assertEquals("the first page must not preload the next one", 0, loadMoreCalls)

        rowsList().performScrollToNode(hasText("Nouveau 60"))
        composeTestRule.waitForIdle()
        assertEquals(1, loadMoreCalls)

        // Last page: scrolling to its end asks for nothing.
        uiState = filmsState(newReleaseItems = newReleaseCards(80), hasMore = false)
        composeTestRule.waitForIdle()
        rowsList().performScrollToNode(hasText("Nouveau 80"))
        composeTestRule.waitForIdle()
        assertEquals(1, loadMoreCalls)
    }

    @Test
    fun aNewOrder_scrollsBackToTheTop() {
        setContent(filmsState(newReleaseItems = newReleaseCards(60)))
        openTab("Films")
        rowsList().performScrollToNode(hasText("Nouveau 60"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertDoesNotExist()

        uiState = uiState.copy(
            selectedMovieSortMode = MovieSortMode.RECENTLY_ADDED,
            newReleases = NewReleasesState(sortMode = MovieSortMode.RECENTLY_ADDED, items = newReleaseCards(60)),
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Nouveau 1").assertIsDisplayed()
    }

    @Test
    fun dpad_goesFromTheFirstCard_upToTheOrderControl_andTheChips_andBack() {
        setContent(filmsState(newReleaseItems = emptyList(), movieLoadState = CatalogTabLoadState.LOADING))
        openTab("Films")
        useDpad()

        // The first card takes focus as soon as the first page lands.
        uiState = filmsState(newReleaseItems = newReleaseCards(8))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Nouveau 1").assertIsFocused()

        pressKeyOnFocused(Key.DirectionUp)
        composeTestRule.onNode(isFocused() and hasAnyAncestor(hasTestTag(MOVIE_SORT_CONTROL_TEST_TAG))).assertExists()

        pressKeyOnFocused(Key.DirectionUp)
        composeTestRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("category-selector-movies"))).assertExists()

        pressKeyOnFocused(Key.DirectionDown)
        composeTestRule.onNode(isFocused() and hasAnyAncestor(hasTestTag(MOVIE_SORT_CONTROL_TEST_TAG))).assertExists()
        sortChip(MovieSortMode.RECENTLY_ADDED).performSemanticsAction(SemanticsActions.RequestFocus)
        pressKeyOnFocused(Key.DirectionCenter)
        assertEquals(listOf(MovieSortMode.RECENTLY_ADDED), pickedModes)

        pressKeyOnFocused(Key.DirectionDown)
        composeTestRule.onNode(isFocused() and hasText("Nouveau", substring = true)).assertExists()
    }

    @Test
    fun focus_staysOnTheChosenChip_whileCategoriesAndNouveautesKeepLoading() {
        setContent(filmsState(newReleaseItems = emptyList(), movieLoadState = CatalogTabLoadState.LOADING))
        openTab("Films")
        useDpad()
        categoryChip("Drame").performSemanticsAction(SemanticsActions.RequestFocus)
        categoryChip("Drame").assertIsFocused()

        val kids = HomeRow(categoryId = "42", title = "Kids", items = listOf(card("k1", "Film Kids")))
        uiState = filmsState(
            newReleaseItems = newReleaseCards(6, prefix = "Arrivé"),
            movieLoadState = CatalogTabLoadState.LOADING,
            movieRows = listOf(actionRow, dramaRow, kids),
        )
        composeTestRule.waitForIdle()

        categoryChip("Drame").assertIsFocused()
        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
    }

    @Test
    fun theChosenCategory_survivesATripToAFilmDetail() {
        val restorationTester = StateRestorationTester(composeTestRule)
        setContent(filmsState(), restorationTester)
        openTab("Films")
        categoryChip("Drame").performClick()
        composeTestRule.waitForIdle()

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        categoryChip("Drame").assertIsSelected()
        composeTestRule.onNodeWithText("Film Drame").assertIsDisplayed()
    }

    @Test
    fun nouveautes_survivesATripToAFilmDetail_withTheOrderControl() {
        val restorationTester = StateRestorationTester(composeTestRule)
        setContent(filmsState(), restorationTester)
        openTab("Films")

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Films") and hasClickAction()).assertIsSelected()
        categoryChip(NEW_RELEASES_LABEL).assertIsSelected()
        composeTestRule.onNodeWithTag(MOVIE_SORT_CONTROL_TEST_TAG).assertExists()
    }
}
