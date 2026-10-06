package com.bobot.iptvapp.ui.screen.player

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import com.bobot.iptvapp.player.PlayerTrack
import com.bobot.iptvapp.player.PlayerTrackType
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose tests of the online subtitle entry point in [PlayerTrackSelectorPanel] and of
 * [OnlineSubtitleSearchPanel]: what each state renders, which callback each row fires, and where
 * the focus lands for a D-pad user. The panels are driven with plain state — no ViewModel, no
 * network — so this runs on any device or emulator.
 *
 * ## Input mode
 * The rows are `clickable`, which Compose only makes focusable outside touch mode
 * (`Focusability.SystemDefined`): on a phone the panels' `requestFocus()` is a no-op until a key
 * is pressed, while a TV starts in keyboard mode. The focus tests therefore compose the panel
 * only once the window is in keyboard mode ([setContentInKeyboardMode]), and
 * [focusRequestsAreIgnoredInTouchMode] pins the touch-mode side of that rule — the one the first
 * run on a phone tripped over.
 *
 * ## Running
 * ```
 * adb.exe shell am instrument -w \
 *   -e class com.bobot.iptvapp.ui.screen.player.OnlineSubtitlePanelsTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OnlineSubtitlePanelsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context = SubtitleSearchContext(
        kind = SubtitleSearchContext.Kind.MOVIE,
        title = "Inception",
        year = 2010,
    )

    private val first = OnlineSubtitle(
        fileId = 1L,
        language = "fr",
        release = "Inception.2010.1080p.BluRay",
        downloadCount = 12,
        featureYear = 2010,
    )
    private val second = OnlineSubtitle(fileId = 2L, language = "fr", fileName = "inception.srt")

    // ── Track selector entry point ──────────────────────────────────────────

    @Test
    fun searchRowShowsAloneOnAVodWithoutTracksAndTakesTheFocus() {
        var opened = 0
        setContentInKeyboardMode {
            PlayerTrackSelectorPanel(
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                onSelectAudio = {},
                onSelectSubtitle = {},
                onDisableSubtitles = {},
                onlineSearchAvailable = true,
                onOpenOnlineSearch = { opened++ },
            )
        }

        composeTestRule.onNodeWithText("Sous-titres").assertExists()
        composeTestRule.onNodeWithText("Désactivés").assertDoesNotExist()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertIsFocused().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun noSearchRowWithoutOnlineSearch() {
        setContentInKeyboardMode {
            PlayerTrackSelectorPanel(
                audioTracks = emptyList(),
                subtitleTracks = listOf(
                    PlayerTrack(
                        id = "sub-fr",
                        label = "Français",
                        languageCode = "fra",
                        isSelected = false,
                        type = PlayerTrackType.SUBTITLE,
                    ),
                ),
                onSelectAudio = {},
                onSelectSubtitle = {},
                onDisableSubtitles = {},
            )
        }

        composeTestRule.onNodeWithText("Désactivés").assertIsFocused()
        composeTestRule.onNodeWithText("Français").assertIsNotFocused()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertDoesNotExist()
    }

    @Test
    fun focusRequestsAreIgnoredInTouchMode() {
        setContentInInputMode(InputMode.Touch) {
            OnlineSubtitleSearchPanel(
                state = OnlineSubtitlesUiState(isAvailable = true, isPanelOpen = true, search = OnlineSubtitleSearchState.Empty),
                context = context,
                onBack = {},
                onRetry = {},
                onSelect = {},
                onDismissError = {},
            )
        }

        composeTestRule.onNodeWithText(ONLINE_SEARCH_RETRY_LABEL).assertIsNotFocused()
    }

    // ── Search panel ────────────────────────────────────────────────────────

    @Test
    fun loadingShowsTheFrenchMessageAndFocusesBack() {
        setSearchPanel(OnlineSubtitlesUiState(isAvailable = true, isPanelOpen = true, search = OnlineSubtitleSearchState.Loading))

        composeTestRule.onNodeWithText(ONLINE_SEARCH_LOADING_MESSAGE).assertExists()
        composeTestRule.onNodeWithText("Inception (2010)").assertExists()
        composeTestRule.onNodeWithText("← $ONLINE_SEARCH_BACK_LABEL").assertIsFocused()
    }

    @Test
    fun resultsAreListedWithHintsAndPickedByHand() {
        val picked = mutableListOf<OnlineSubtitle>()
        setSearchPanel(
            state = OnlineSubtitlesUiState(
                isAvailable = true,
                isPanelOpen = true,
                search = OnlineSubtitleSearchState.Results(listOf(first, second)),
                applyingFileId = 2L,
            ),
            onSelect = { picked += it },
        )

        composeTestRule.onNodeWithText(first.release!!).assertIsFocused()
        composeTestRule.onNodeWithText("Français · 2010 · 12 téléchargements").assertExists()
        composeTestRule.onNodeWithText(ONLINE_SUBTITLE_APPLYING_LABEL).assertExists()
        assertTrue(picked.isEmpty())

        composeTestRule.onNode(hasText("inception.srt") and hasClickAction())
            .performClick()

        assertEquals(listOf(second), picked)
    }

    @Test
    fun retryableFailureOffersRetry() {
        var retried = 0
        setSearchPanel(
            state = OnlineSubtitlesUiState(
                isAvailable = true,
                isPanelOpen = true,
                search = OnlineSubtitleSearchState.Failed(NETWORK_MESSAGE, canRetry = true),
            ),
            onRetry = { retried++ },
        )

        composeTestRule.onNodeWithText(NETWORK_MESSAGE).assertExists()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_RETRY_LABEL).assertIsFocused()
        composeTestRule.onNodeWithText("← $ONLINE_SEARCH_BACK_LABEL").assertIsNotFocused()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_RETRY_LABEL).performClick()

        assertEquals(1, retried)
    }

    @Test
    fun missingKeyHasNoRetry() {
        setSearchPanel(
            OnlineSubtitlesUiState(
                isAvailable = true,
                isPanelOpen = true,
                search = OnlineSubtitleSearchState.Failed(MISSING_KEY_MESSAGE, canRetry = false),
            ),
        )

        composeTestRule.onNodeWithText(MISSING_KEY_MESSAGE).assertExists()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_RETRY_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText("← $ONLINE_SEARCH_BACK_LABEL").assertIsFocused()
    }

    @Test
    fun emptyOffersRetry() {
        setSearchPanel(OnlineSubtitlesUiState(isAvailable = true, isPanelOpen = true, search = OnlineSubtitleSearchState.Empty))

        composeTestRule.onNodeWithText(ONLINE_SEARCH_EMPTY_MESSAGE).assertExists()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_RETRY_LABEL).assertIsFocused()
    }

    @Test
    fun applyErrorIsShownAndDismissed() {
        var dismissed = 0
        var back = 0
        setSearchPanel(
            state = OnlineSubtitlesUiState(
                isAvailable = true,
                isPanelOpen = true,
                search = OnlineSubtitleSearchState.Results(listOf(first)),
                applyError = QUOTA_MESSAGE,
            ),
            onDismissError = { dismissed++ },
            onBack = { back++ },
        )

        composeTestRule.onNodeWithText(QUOTA_MESSAGE).assertExists()
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.onNodeWithText("← $ONLINE_SEARCH_BACK_LABEL").performClick()

        assertEquals(1, dismissed)
        assertEquals(1, back)
    }

    // ── Full transition, driven by real key events ──────────────────────────

    /**
     * CC → track list → search → track list → CC, with the D-pad and BACK sent as key events
     * through the activity (see [pressKey] — BACK reaches the [BackHandler] the way a remote's
     * does), and the
     * focus checked at every stop: first row on opening, the search's own targets as it changes
     * shape, a row picked by hand kept through a pick in progress, the search row on the way
     * back, the CC button once everything is closed.
     */
    @Test
    fun dpadWalksSelectorToSearchAndBackWithTheFocusAtEachStep() {
        val harness = TrackPanelsHarness()
        setContentInKeyboardMode { harness.Content() }

        // Nothing open: the CC button starts with the focus, as after a previous close.
        composeTestRule.runOnIdle { harness.refocusButton = true }
        composeTestRule.onNodeWithText("CC").assertIsFocused()

        // CC → the track list opens on its first row, not on the search row.
        pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
        composeTestRule.onNodeWithText("Désactivés").assertIsFocused()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertIsNotFocused()

        pressKey(KeyEvent.KEYCODE_DPAD_DOWN)
        pressKey(KeyEvent.KEYCODE_DPAD_DOWN)
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertIsFocused()

        // → the search: "Retour aux pistes" while it runs, then the first result.
        pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
        composeTestRule.onNodeWithText("← $ONLINE_SEARCH_BACK_LABEL").assertIsFocused()
        composeTestRule.runOnIdle { harness.search = harness.search.copy(search = OnlineSubtitleSearchState.Results(listOf(first, second))) }
        composeTestRule.onNodeWithText(first.release!!).assertIsFocused()

        // A row walked to by hand stays focused through the pick it starts.
        pressKey(KeyEvent.KEYCODE_DPAD_DOWN)
        composeTestRule.onNode(hasText("inception.srt") and hasClickAction()).assertIsFocused()
        composeTestRule.runOnIdle { harness.search = harness.search.copy(applyingFileId = second.fileId) }
        composeTestRule.onNode(hasText("inception.srt") and hasClickAction()).assertIsFocused()
        composeTestRule.runOnIdle { harness.search = harness.search.copy(applyingFileId = null, appliedFileId = second.fileId) }
        composeTestRule.onNode(hasText("inception.srt") and hasClickAction()).assertIsFocused()

        // BACK → the track list, on the row that opened the search.
        pressKey(KeyEvent.KEYCODE_BACK)
        composeTestRule.onNodeWithText(ONLINE_SEARCH_BACK_LABEL, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertIsFocused()
        composeTestRule.onNodeWithText("Désactivés").assertIsNotFocused()

        // Same way back through the "Retour aux pistes" row.
        pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
        composeTestRule.onNodeWithText("← $ONLINE_SEARCH_BACK_LABEL").assertIsFocused()
        pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertIsFocused()

        // BACK again → everything closed, the D-pad back on CC.
        pressKey(KeyEvent.KEYCODE_BACK)
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText("CC").assertIsFocused()
        composeTestRule.runOnIdle { assertEquals(false, harness.refocusButton) }
    }

    /** The same walk by touch lights up nothing: no row, and not the CC button on the way out. */
    @Test
    fun touchTransitionNeverTakesTheFocus() {
        val harness = TrackPanelsHarness()
        setContentInInputMode(InputMode.Touch) { harness.Content() }

        composeTestRule.onNodeWithText("CC").performClick()
        composeTestRule.onNodeWithText("Désactivés").assertIsNotFocused()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertIsNotFocused().performClick()
        composeTestRule.onNodeWithText("← $ONLINE_SEARCH_BACK_LABEL").assertIsNotFocused().performClick()
        composeTestRule.onNodeWithText(ONLINE_SEARCH_ROW_LABEL).assertIsNotFocused()

        pressKey(KeyEvent.KEYCODE_BACK)
        composeTestRule.onNodeWithText("CC").assertIsNotFocused()
        composeTestRule.runOnIdle { assertEquals(false, harness.refocusButton) }
    }

    /**
     * [PlayerScreen]'s panel wiring on plain state: the CC button while nothing is open, then the
     * track list or the search in its place, BACK walking one level at a time — the same flags
     * ([PlayerScreen]'s `returnedFromSearch`, `refocusTracksButton`) set at the same moments.
     */
    private inner class TrackPanelsHarness {
        var selectorVisible by mutableStateOf(false)
        var searchOpen by mutableStateOf(false)
        var returnedFromSearch by mutableStateOf(false)
        var refocusButton by mutableStateOf(false)
        var search by mutableStateOf(
            OnlineSubtitlesUiState(isAvailable = true, isPanelOpen = true, search = OnlineSubtitleSearchState.Loading),
        )

        private fun closeSearch() {
            searchOpen = false
            selectorVisible = true
            returnedFromSearch = true
            search = search.copy(search = OnlineSubtitleSearchState.Loading)
        }

        @Composable
        fun Content() {
            BackHandler(enabled = selectorVisible || searchOpen) {
                if (searchOpen) {
                    closeSearch()
                } else {
                    selectorVisible = false
                    returnedFromSearch = false
                    refocusButton = true
                }
            }
            Column {
                if (!selectorVisible && !searchOpen) {
                    PlayerTracksButton(
                        onClick = {
                            returnedFromSearch = false
                            refocusButton = false
                            selectorVisible = true
                        },
                        takeFocus = refocusButton,
                        onFocusTaken = { refocusButton = false },
                    )
                }
                if (searchOpen) {
                    OnlineSubtitleSearchPanel(
                        state = search,
                        context = context,
                        onBack = ::closeSearch,
                        onRetry = {},
                        onSelect = {},
                        onDismissError = {},
                    )
                } else if (selectorVisible) {
                    PlayerTrackSelectorPanel(
                        audioTracks = emptyList(),
                        subtitleTracks = listOf(
                            PlayerTrack(
                                id = "sub-fr",
                                label = "Français",
                                languageCode = "fra",
                                isSelected = true,
                                type = PlayerTrackType.SUBTITLE,
                            ),
                        ),
                        onSelectAudio = {},
                        onSelectSubtitle = {},
                        onDisableSubtitles = {},
                        onlineSearchAvailable = true,
                        onOpenOnlineSearch = { searchOpen = true },
                        focusOnlineSearchRow = returnedFromSearch,
                    )
                }
            }
        }
    }

    /**
     * One key press, down then up, handed to the test activity the way the window hands it a
     * remote's key: through `Activity.dispatchKeyEvent`, so Compose's D-pad focus moves and
     * clickable handling run for real, and an unconsumed BACK falls through to `onBackPressed`
     * and the [BackHandler]. Not `Instrumentation.sendKeyDownUpSync`: that injects into whichever
     * window holds the system input focus, and on the SM-S948B a system overlay
     * (`APPLICATION_OVERLAY`, owner `android`) keeps it, so the test activity never saw a key.
     */
    private fun pressKey(keyCode: Int) {
        val activity = composeTestRule.activity
        composeTestRule.runOnUiThread {
            val consumedDown = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            val consumedUp = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            assertTrue("key $keyCode was not handled", consumedDown || consumedUp)
        }
        composeTestRule.waitForIdle()
    }

    private fun setSearchPanel(
        state: OnlineSubtitlesUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSelect: (OnlineSubtitle) -> Unit = {},
        onDismissError: () -> Unit = {},
    ) {
        setContentInKeyboardMode {
            OnlineSubtitleSearchPanel(
                state = state,
                context = context,
                onBack = onBack,
                onRetry = onRetry,
                onSelect = onSelect,
                onDismissError = onDismissError,
            )
        }
    }

    /**
     * Keyboard mode is where a TV starts, and where a phone goes on the first D-pad press: [content]
     * then makes its initial focus request under the same input mode as on a TV.
     */
    private fun setContentInKeyboardMode(content: @Composable () -> Unit) =
        setContentInInputMode(InputMode.Keyboard, content)

    /**
     * Puts the window in [mode] — a focus request out of touch mode, the instrumentation's
     * `setInTouchMode` into it — and only then composes [content], so its `LaunchedEffect` focus
     * request runs under [mode] whatever the device and whatever mode the previous test left the
     * display in. A `performTouchInput` tap would not do: Compose dispatches it straight to its
     * view, past the `ViewRootImpl` that flips touch mode. The switch goes through the window
     * manager and lands asynchronously, hence the wait; a switch that never lands fails here
     * rather than as a misleading focus assertion.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun setContentInInputMode(mode: InputMode, content: @Composable () -> Unit) {
        lateinit var inputModeManager: InputModeManager
        var showContent by mutableStateOf(false)
        composeTestRule.setContent {
            inputModeManager = LocalInputModeManager.current
            IptvAppTheme {
                if (showContent) content()
            }
        }
        if (mode == InputMode.Keyboard) {
            composeTestRule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        } else {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        }
        composeTestRule.waitUntil(INPUT_MODE_TIMEOUT_MS) { inputModeManager.inputMode == mode }
        composeTestRule.runOnIdle { showContent = true }
    }

    private companion object {
        const val INPUT_MODE_TIMEOUT_MS = 5_000L
    }
}
