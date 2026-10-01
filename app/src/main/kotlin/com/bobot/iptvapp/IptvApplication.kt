package com.bobot.iptvapp

import android.app.Application
import com.bobot.iptvapp.data.remote.opensubtitles.OnlineSubtitleOrphanSweeper
import com.bobot.iptvapp.download.DownloadRequirementsController
import com.bobot.iptvapp.download.DownloadTracker
import com.bobot.iptvapp.download.LogoutPurgeRecoveryController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point for Hilt dependency injection.
 *
 * The [@HiltAndroidApp] annotation triggers Hilt's code generation,
 * which produces a Hilt component attached to the Application lifecycle.
 * This component is the root of the DI hierarchy — all other Hilt components
 * (ActivityComponent, ViewModelComponent, …) are sub-components of it.
 *
 * Registered in AndroidManifest.xml via android:name=".IptvApplication"
 * on the <application> element.
 *
 * No application-level initialisation beyond Hilt is needed at this stage;
 * Tasks 6–12 may add library inits here (e.g. Coil ImageLoader, Timber).
 */
@HiltAndroidApp
class IptvApplication : Application() {
    @Inject lateinit var downloadTracker: DownloadTracker
    @Inject lateinit var downloadRequirementsController: DownloadRequirementsController

    /**
     * Injected purely so Hilt builds it: its constructor resumes a logout purge left pending by a
     * previous run, and cleans up installs logged out before that purge existed.
     */
    @Inject lateinit var logoutPurgeRecoveryController: LogoutPurgeRecoveryController

    /** Injected purely so Hilt builds it: its constructor sweeps a dead process's subtitle files. */
    @Inject lateinit var onlineSubtitleOrphanSweeper: OnlineSubtitleOrphanSweeper
}
