package com.bobot.iptvapp.ui.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Shared helper requesting the runtime `POST_NOTIFICATIONS` permission (Android 13+), used by the
 * movie and series detail screens so a download's progress notification (see
 * `IptvDownloadService`) can actually be shown to the user.
 *
 * The returned lambda is meant to be called right before firing a download action. It never
 * blocks or gates that action: the permission request (if any) and the download are independent —
 * the request is fire-and-forget, and the caller must invoke the download action unconditionally
 * regardless of the outcome.
 *
 * The request is a no-op when any of the following holds:
 * - the OS is older than Android 13 (`TIRAMISU`), where the permission does not exist;
 * - the device is an Android TV (see [rememberIsTvDevice]) — TV apps do not show this system
 *   prompt in the same way and downloads are not the primary TV use case;
 * - the permission is already granted.
 *
 * No "already asked" flag is persisted — repeated calls simply re-check `checkSelfPermission` and
 * rely on the OS's own policy for a previously-denied prompt (e.g. not re-showing it after a
 * "don't ask again").
 */
@Composable
fun rememberNotificationPermissionRequester(): () -> Unit {
    val context = LocalContext.current
    val isTv = rememberIsTvDevice()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )

    return remember(context, isTv, launcher) {
        {
            val shouldRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isTv &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (shouldRequest) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
