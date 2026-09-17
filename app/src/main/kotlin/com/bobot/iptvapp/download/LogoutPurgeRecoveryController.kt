package com.bobot.iptvapp.download

import com.bobot.iptvapp.di.IoDispatcher
import com.bobot.iptvapp.domain.logout.LogoutPurger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finishes, at application start, any logout purge this install still owes.
 *
 * Follows [DownloadRequirementsController]'s shape: a `@Singleton` whose `init` starts one
 * application-scoped coroutine, held by an `@Inject` field on
 * [com.bobot.iptvapp.IptvApplication] so Hilt actually instantiates it.
 *
 * ## Why failures are swallowed here and nowhere else
 * This runs with no user watching and no screen to report to. A purge that fails at startup leaves
 * the pending marker exactly as it found it, so the next launch tries again and the enqueue guard
 * keeps refusing new downloads in the meantime — the state stays correct. Letting the exception out
 * of this scope would only crash the app on launch, which neither fixes the data nor tells anyone
 * anything.
 */
@Singleton
class LogoutPurgeRecoveryController @Inject constructor(
    private val logoutPurger: LogoutPurger,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        scope.launch {
            runCatching { logoutPurger.recoverIfNeeded() }
        }
    }
}
