package com.bobot.iptvapp.ui.screen.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Reactive state for the offline downloads library. */
data class DownloadsUiState(
    val downloads: List<OfflineDownload> = emptyList(),
    val isLoading: Boolean = true,
)

/** Presents the persisted Media3 download queue and forwards queue actions to its repository. */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = downloadRepository.observeDownloads()
        .map { downloads -> DownloadsUiState(downloads = downloads, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DownloadsUiState(),
        )

    fun pause(downloadId: String) = runAction { downloadRepository.pause(downloadId) }

    fun resume(downloadId: String) = runAction { downloadRepository.resume(downloadId) }

    fun remove(downloadId: String) = runAction { downloadRepository.remove(downloadId) }

    private fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}
