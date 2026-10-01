package com.bobot.iptvapp.ui.screen.player

import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult

/**
 * The online subtitle search, as [PlayerScreen] renders it — part of [PlayerUiState].
 *
 * @property isAvailable  Whether a search can be offered at all: a movie or an episode whose route
 *                        carried a [com.bobot.iptvapp.domain.model.SubtitleSearchContext]. Never
 *                        `true` for a live channel.
 * @property isPanelOpen  Whether the search panel is showing.
 * @property search       Where the current search stands.
 * @property applyingFileId The subtitle being downloaded and loaded into the player, if any.
 * @property appliedFileId  The online subtitle the player currently carries, if any.
 * @property applyError   French message for the last failed pick; the video keeps playing.
 */
data class OnlineSubtitlesUiState(
    val isAvailable: Boolean = false,
    val isPanelOpen: Boolean = false,
    val search: OnlineSubtitleSearchState = OnlineSubtitleSearchState.Idle,
    val applyingFileId: Long? = null,
    val appliedFileId: Long? = null,
    val applyError: String? = null,
)

/** One online search, from nothing asked yet to its outcome. */
sealed interface OnlineSubtitleSearchState {

    /** No search asked for yet, or the last one was cancelled. */
    data object Idle : OnlineSubtitleSearchState

    data object Loading : OnlineSubtitleSearchState

    /** Never empty; French first. The user picks one by hand — nothing is applied on its own. */
    data class Results(val subtitles: List<OnlineSubtitle>) : OnlineSubtitleSearchState

    /** The provider answered, with nothing for this content. */
    data object Empty : OnlineSubtitleSearchState

    /**
     * @property canRetry `false` when trying again cannot help until something changes in
     *                    Réglages (no key, key refused).
     */
    data class Failed(val message: String, val canRetry: Boolean) : OnlineSubtitleSearchState
}

internal const val MISSING_KEY_MESSAGE =
    "Ajoutez une clé API OpenSubtitles dans Réglages pour chercher des sous-titres."
internal const val KEY_REFUSED_MESSAGE =
    "OpenSubtitles a refusé la clé API. Vérifiez-la dans Réglages."
internal const val NETWORK_MESSAGE =
    "Impossible de joindre OpenSubtitles. Vérifiez la connexion puis réessayez."
internal const val SERVER_MESSAGE =
    "OpenSubtitles est momentanément indisponible. Réessayez dans un instant."
internal const val UNEXPECTED_MESSAGE =
    "OpenSubtitles a répondu de façon inattendue. Réessayez plus tard."
internal const val QUOTA_MESSAGE =
    "Quota quotidien de téléchargements OpenSubtitles atteint. Il se renouvelle à minuit (UTC)."
internal const val LINK_EXPIRED_MESSAGE =
    "Le lien de téléchargement a expiré. Réessayez pour en obtenir un nouveau."
internal const val INVALID_FORMAT_MESSAGE =
    "Ce fichier n'est pas un sous-titre SRT lisible. Choisissez-en un autre."
internal const val TOO_LARGE_MESSAGE =
    "Ce fichier de sous-titres est trop volumineux. Choisissez-en un autre."
internal const val UNAVAILABLE_FILE_MESSAGE =
    "Ce sous-titre n'est plus disponible sur OpenSubtitles. Choisissez-en un autre."
internal const val STORAGE_MESSAGE =
    "Impossible d'enregistrer le sous-titre sur l'appareil."
internal const val LOGOUT_MESSAGE =
    "Sous-titre ignoré : une déconnexion est en cours."
internal const val ONLINE_TRACK_FAILED_MESSAGE =
    "Le sous-titre choisi n'a pas pu être lu. La lecture continue sans lui."

internal fun rateLimitedMessage(retryAfterSeconds: Int?): String =
    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
        "Trop de demandes envoyées à OpenSubtitles. Réessayez dans $retryAfterSeconds s."
    } else {
        "Trop de demandes envoyées à OpenSubtitles. Réessayez dans un instant."
    }

internal fun OnlineSubtitleSearchResult.Failed.toSearchState(): OnlineSubtitleSearchState.Failed =
    when (reason) {
        OnlineSubtitleSearchResult.Reason.MISSING_API_KEY ->
            OnlineSubtitleSearchState.Failed(MISSING_KEY_MESSAGE, canRetry = false)
        OnlineSubtitleSearchResult.Reason.UNAUTHORIZED,
        OnlineSubtitleSearchResult.Reason.FORBIDDEN ->
            OnlineSubtitleSearchState.Failed(KEY_REFUSED_MESSAGE, canRetry = false)
        OnlineSubtitleSearchResult.Reason.RATE_LIMITED ->
            OnlineSubtitleSearchState.Failed(rateLimitedMessage(retryAfterSeconds), canRetry = true)
        OnlineSubtitleSearchResult.Reason.TIMEOUT,
        OnlineSubtitleSearchResult.Reason.NETWORK ->
            OnlineSubtitleSearchState.Failed(NETWORK_MESSAGE, canRetry = true)
        OnlineSubtitleSearchResult.Reason.SERVER ->
            OnlineSubtitleSearchState.Failed(SERVER_MESSAGE, canRetry = true)
        OnlineSubtitleSearchResult.Reason.INVALID_RESPONSE,
        OnlineSubtitleSearchResult.Reason.REDIRECT_REFUSED,
        OnlineSubtitleSearchResult.Reason.UNEXPECTED_HTTP ->
            OnlineSubtitleSearchState.Failed(UNEXPECTED_MESSAGE, canRetry = true)
    }

internal fun OnlineSubtitleDownloadResult.Failed.toMessage(): String =
    when (reason) {
        OnlineSubtitleDownloadResult.Reason.MISSING_API_KEY -> MISSING_KEY_MESSAGE
        OnlineSubtitleDownloadResult.Reason.UNAUTHORIZED,
        OnlineSubtitleDownloadResult.Reason.FORBIDDEN -> KEY_REFUSED_MESSAGE
        OnlineSubtitleDownloadResult.Reason.QUOTA_EXCEEDED -> QUOTA_MESSAGE
        OnlineSubtitleDownloadResult.Reason.RATE_LIMITED -> rateLimitedMessage(retryAfterSeconds)
        OnlineSubtitleDownloadResult.Reason.LINK_EXPIRED -> LINK_EXPIRED_MESSAGE
        OnlineSubtitleDownloadResult.Reason.UNSUPPORTED_FORMAT -> INVALID_FORMAT_MESSAGE
        OnlineSubtitleDownloadResult.Reason.TOO_LARGE -> TOO_LARGE_MESSAGE
        OnlineSubtitleDownloadResult.Reason.INVALID_FILE_ID -> UNAVAILABLE_FILE_MESSAGE
        OnlineSubtitleDownloadResult.Reason.TIMEOUT,
        OnlineSubtitleDownloadResult.Reason.NETWORK -> NETWORK_MESSAGE
        OnlineSubtitleDownloadResult.Reason.SERVER -> SERVER_MESSAGE
        OnlineSubtitleDownloadResult.Reason.STORAGE -> STORAGE_MESSAGE
        OnlineSubtitleDownloadResult.Reason.REDIRECT_REFUSED,
        OnlineSubtitleDownloadResult.Reason.UNTRUSTED_LINK,
        OnlineSubtitleDownloadResult.Reason.INVALID_RESPONSE,
        OnlineSubtitleDownloadResult.Reason.UNEXPECTED_HTTP -> UNEXPECTED_MESSAGE
        OnlineSubtitleDownloadResult.Reason.SESSION_ENDED -> LOGOUT_MESSAGE
    }
