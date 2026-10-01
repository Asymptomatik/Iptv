package com.bobot.iptvapp.ui.screen.player

import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import java.util.Locale

/**
 * Framework-free wording of the online subtitle search panel — same split as
 * [PlayerTrackSelection][hasSelectableTracks]: `OnlineSubtitleSearchPanel` only renders what these
 * functions return, and `OnlineSubtitlePresentationTest` pins the wording on the JVM.
 */

/** Label of the row that opens the online search from the track selector. */
internal const val ONLINE_SEARCH_ROW_LABEL = "Rechercher en ligne…"

internal const val ONLINE_SEARCH_TITLE = "Sous-titres en ligne"
internal const val ONLINE_SEARCH_BACK_LABEL = "Retour aux pistes"
internal const val ONLINE_SEARCH_LOADING_MESSAGE = "Recherche de sous-titres français…"
internal const val ONLINE_SEARCH_EMPTY_MESSAGE = "Aucun sous-titre trouvé pour ce contenu."
internal const val ONLINE_SEARCH_RETRY_LABEL = "Réessayer"
internal const val ONLINE_SUBTITLE_APPLYING_LABEL = "Téléchargement…"

/** Hint on a movie result the provider did not tie to the searched title — see [OnlineSubtitle.FeatureMatch]. */
internal const val ONLINE_SUBTITLE_UNCONFIRMED_HINT = "Film non confirmé"

/**
 * What the search is about, shown under the panel title: `"Inception (2010)"` for a movie,
 * `"Breaking Bad · S01E02"` for an episode — so the user can tell a wrong match from a missing
 * subtitle before blaming the results.
 */
internal fun describeSearchContext(context: SubtitleSearchContext): String {
    return when (context.kind) {
        SubtitleSearchContext.Kind.MOVIE ->
            if (context.year != null) "${context.title} (${context.year})" else context.title
        SubtitleSearchContext.Kind.EPISODE -> {
            val show = context.seriesTitle ?: context.title
            val code = episodeCode(context.seasonNumber, context.episodeNumber)
            if (code != null) "$show · $code" else show
        }
    }
}

/**
 * Main line of a result row: the release name the file was synced against, which is what tells two
 * French files apart — then the file name, then the matched title, then the bare file id.
 */
internal fun onlineSubtitleTitle(subtitle: OnlineSubtitle): String {
    return subtitle.release?.takeIf { it.isNotBlank() }
        ?: subtitle.fileName?.takeIf { it.isNotBlank() }
        ?: subtitle.featureTitle?.takeIf { it.isNotBlank() }
        ?: "Sous-titre n° ${subtitle.fileId}"
}

/**
 * Second line of a result row: language first, then whatever hints the provider gave — year,
 * episode, popularity and quality flags — joined with " · ". Hints the provider left out are
 * skipped rather than shown as blanks.
 */
internal fun onlineSubtitleHints(subtitle: OnlineSubtitle): String {
    return buildList {
        add(languageDisplayName(subtitle.language))
        if (subtitle.featureMatch == OnlineSubtitle.FeatureMatch.UNCONFIRMED) add(ONLINE_SUBTITLE_UNCONFIRMED_HINT)
        subtitle.featureYear?.let { add(it.toString()) }
        episodeCode(subtitle.seasonNumber, subtitle.episodeNumber)?.let { add(it) }
        add(downloadCountLabel(subtitle.downloadCount))
        if (subtitle.isHearingImpaired) add("Malentendants")
        if (subtitle.isMachineTranslated || subtitle.isAiTranslated) add("Traduction automatique")
        if (subtitle.isFromTrusted) add("Source fiable")
    }.joinToString(separator = " · ")
}

/**
 * French name of a provider language code (`"fr"` → `"Français"`, `"pt-br"` →
 * `"Portugais (Brésil)"`); the code itself, upper-cased, when the platform does not know it.
 */
internal fun languageDisplayName(code: String): String {
    val locale = Locale.forLanguageTag(code)
    val name = locale.getDisplayName(Locale.FRENCH)
    if (name.isBlank() || name.equals(code, ignoreCase = true) || locale.language.isEmpty()) {
        return code.uppercase(Locale.ROOT)
    }
    return name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

private fun downloadCountLabel(count: Int): String =
    if (count == 1) "1 téléchargement" else "${count.coerceAtLeast(0)} téléchargements"

/** `"S01E02"`, `"S01"`, `"E02"`, or `null` when both numbers are unknown. */
private fun episodeCode(season: Int?, episode: Int?): String? {
    val s = season?.let { "S%02d".format(Locale.ROOT, it) }
    val e = episode?.let { "E%02d".format(Locale.ROOT, it) }
    return if (s == null && e == null) null else (s.orEmpty() + e.orEmpty())
}
