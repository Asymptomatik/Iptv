package com.bobot.iptvapp.ui.screen.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.repository.DownloadRepository
import com.bobot.iptvapp.ui.util.DOWNLOAD_REFUSED_MESSAGE
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reactive state for the offline downloads library.
 *
 * @property actionMessage Transient feedback about the last queue action, or `null`. Kept here
 *   rather than thrown away like the action's result used to be: a pause or resume refused because
 *   a logout purge is running looks exactly like a button that does not work.
 */
data class DownloadsUiState(
    val downloads: List<OfflineDownload> = emptyList(),
    val isLoading: Boolean = true,
    val actionMessage: String? = null,
)

/** Presents the persisted Media3 download queue and forwards queue actions to its repository. */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val actionMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadRepository.observeDownloads(),
        actionMessage,
    ) { downloads, message ->
        DownloadsUiState(downloads = downloads, isLoading = false, actionMessage = message)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DownloadsUiState(),
    )

    fun pause(downloadId: String) = runRefusableAction { downloadRepository.pause(downloadId) }

    fun resume(downloadId: String) = runRefusableAction { downloadRepository.resume(downloadId) }

    /** Unguarded, like the repository call it forwards to — see [DownloadRepository.remove]. */
    fun remove(downloadId: String) {
        viewModelScope.launch { downloadRepository.remove(downloadId) }
    }

    /** Acknowledges [DownloadsUiState.actionMessage] once shown, so it is not shown twice. */
    fun onActionMessageShown() {
        actionMessage.value = null
    }

    /** @param action returns `false` when the purge barrier refused it, never on a real failure. */
    private fun runRefusableAction(action: suspend () -> Boolean) {
        viewModelScope.launch {
            if (!action()) actionMessage.value = DOWNLOAD_REFUSED_MESSAGE
        }
    }
}
