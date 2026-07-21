package com.bobot.iptvapp.domain.model

/**
 * Lifecycle states exposed by the offline-download feature.
 *
 * The values are persisted as enum names in Room so that the UI can render a stable state even
 * while the Media3 download service is not running. The actual Media3 state is translated by the
 * download tracker before it reaches this model.
 */
enum class DownloadState(
    val label: String,
) {
    NOT_DOWNLOADED("À télécharger"),
    QUEUED("En attente"),
    DOWNLOADING("Téléchargement…"),
    PAUSED("En pause"),
    COMPLETED("Téléchargé"),
    FAILED("Erreur"),
}
