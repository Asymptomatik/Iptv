package com.bobot.iptvapp.download

import com.bobot.iptvapp.data.logout.LogoutCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finishes, at application start, any logout purge this install still owes.
 *
 * Follows [DownloadRequirementsController]'s shape: a `@Singleton` held by an `@Inject` field on
 * [com.bobot.iptvapp.IptvApplication] so Hilt actually instantiates it.
 *
 * ## Why this exists next to [com.bobot.iptvapp.MainViewModel]'s own recovery
 * That one covers the launch the user can see: it blocks the start destination until the purge is
 * resolved. This one covers the launches they cannot — the process brought up by the download
 * service alone, with no Activity and no start destination to block. Both go through the same
 * [LogoutCoordinator], so whichever arrives second finds the purge already running and waits rather
 * than starting a second one.
 *
 * ## Why failures are swallowed here and nowhere else
 * This runs with no user watching and no screen to report to. A purge that fails at startup leaves
 * the pending marker exactly as it found it, so the next launch tries again and the enqueue guard
 * keeps refusing new downloads in the meantime — the state stays correct. The failure is published
 * on [LogoutCoordinator.state] for any screen that does come up. Letting the exception out of the
 * application scope would only crash the app on launch, which neither fixes the data nor tells
 * anyone anything.
 */
@Singleton
class LogoutPurgeRecoveryController @Inject constructor(
    logoutCoordinator: LogoutCoordinator,
) {
    init {
        logoutCoordinator.startRecovery()
    }
}
