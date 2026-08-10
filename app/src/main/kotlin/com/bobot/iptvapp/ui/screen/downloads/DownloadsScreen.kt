package com.bobot.iptvapp.ui.screen.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.util.StreamTitle
import com.bobot.iptvapp.ui.components.FocusableTextButton
import com.bobot.iptvapp.ui.components.GhostButton
import com.bobot.iptvapp.ui.components.GlassSurface
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.RadiusLg
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import com.bobot.iptvapp.ui.util.rememberIsTvDevice
import com.bobot.iptvapp.ui.theme.LayoutDimens

/**
 * Offline library for queued and completed VOD downloads.
 *
 * Playback stays a navigation concern: [onPlay] receives the persisted stream URL and source id
 * only for completed files, then the app graph opens the existing Player destination.
 */
@Composable
fun DownloadsScreen(
    onNavigateBack: () -> Unit,
    onPlay: (streamUrl: String, streamId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DownloadsContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onPlay = onPlay,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onRemove = viewModel::remove,
        modifier = modifier,
    )
}

@Composable
internal fun DownloadsContent(
    uiState: DownloadsUiState,
    onNavigateBack: () -> Unit,
    onPlay: (streamUrl: String, streamId: String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = if (rememberIsTvDevice()) {
        LayoutDimens.ContentPaddingTv
    } else {
        LayoutDimens.ContentPaddingPhone
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = horizontalPadding, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            FocusableTextButton(label = "Retour", onClick = onNavigateBack)
            Text(
                text = "Téléchargements",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
        }

        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentSolid)
            }

            uiState.downloads.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(horizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Aucun téléchargement hors ligne.",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = horizontalPadding,
                    vertical = Spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(uiState.downloads, key = { it.downloadId }) { download ->
                    DownloadCard(
                        download = download,
                        onPlay = onPlay,
                        onPause = onPause,
                        onResume = onResume,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    download: OfflineDownload,
    onPlay: (streamUrl: String, streamId: String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    GlassSurface(shape = RoundedCornerShape(RadiusLg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AsyncImage(
                model = download.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color.DarkGray),
                error = ColorPainter(Color.DarkGray),
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(RadiusLg)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Downloads enqueued from now on already store the stripped title (QA finding
                    // N4); stripping again here costs nothing and also cleans up the rows that
                    // were enqueued before, which keep the provider prefix in the database.
                    text = StreamTitle.displayTitle(download.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = download.state.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (download.state == DownloadState.FAILED) SemanticError else TextSecondary,
                )
                download.progressPercent?.let { progress ->
                    Spacer(Modifier.height(Spacing.sm))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentSolid,
                    )
                    Text(
                        text = "$progress %",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                DownloadActions(
                    download = download,
                    onPlay = onPlay,
                    onPause = onPause,
                    onResume = onResume,
                    onRemove = onRemove,
                )
            }
        }
    }
}

@Composable
private fun DownloadActions(
    download: OfflineDownload,
    onPlay: (streamUrl: String, streamId: String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Spacer(Modifier.height(Spacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (download.state) {
            DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
                GhostButton(label = "Pause", onClick = { onPause(download.downloadId) })
            }

            DownloadState.PAUSED, DownloadState.FAILED -> {
                GhostButton(label = "Reprendre", onClick = { onResume(download.downloadId) })
            }

            DownloadState.COMPLETED -> {
                GhostButton(
                    label = "Lire",
                    onClick = { onPlay(download.streamUrl, download.contentId) },
                )
            }

            DownloadState.NOT_DOWNLOADED -> Unit
        }
        GhostButton(label = "Supprimer", onClick = { onRemove(download.downloadId) })
    }
}
