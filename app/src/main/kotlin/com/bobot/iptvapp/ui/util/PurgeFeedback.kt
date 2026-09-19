package com.bobot.iptvapp.ui.util

/**
 * User-facing French wording for the two things a pending logout purge refuses.
 *
 * One file rather than a copy per screen: both refusals have a single cause — a purge is running
 * or still owed, see [com.bobot.iptvapp.data.logout.LogoutCoordinator.runUnlessPurgeOwed] — and
 * several screens hit each of them, so separately-worded copies would only drift apart.
 *
 * Neither message offers an action, because there is none: the purge either finishes, or fails and
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModel] surfaces its own retryable error. They
 * say what is happening and that it is temporary, which is all the user can act on.
 */

/**
 * Shown when a download command comes back refused —
 * [com.bobot.iptvapp.domain.repository.DownloadRepository.enqueue] returning `null`, or
 * [com.bobot.iptvapp.domain.repository.DownloadRepository.pause] /
 * [com.bobot.iptvapp.domain.repository.DownloadRepository.resume] returning `false`.
 */
const val DOWNLOAD_REFUSED_MESSAGE: String =
    "Déconnexion en cours : les téléchargements sont indisponibles le temps que " +
        "les données du compte soient effacées."

/**
 * Shown when writing new credentials is refused, on the onboarding form and on Réglages' identifier
 * form alike.
 *
 * A sign-in accepted mid-purge is the worst of the refusals to let through: it writes the *new*
 * account's credentials into the very DataStore the purge is about to finalize, so the purge's last
 * step either erases a session the user just created or, worse, the purge's own residue checks
 * start attributing the previous account's leftovers to the new one.
 */
const val CREDENTIALS_REFUSED_MESSAGE: String =
    "Les données du compte précédent sont encore en cours de suppression. " +
        "Patientez quelques instants puis réessayez."
