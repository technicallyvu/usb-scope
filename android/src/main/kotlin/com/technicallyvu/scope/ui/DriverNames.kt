package com.technicallyvu.scope.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.technicallyvu.scope.R

/**
 * Maps a driver id to the name the user sees.
 *
 * The ids (and `core`'s own `DeviceDriver.displayName`s) describe the protocol, not the product:
 * they are engineering labels, and one of them carries a vendor-derived word. Nothing outside a
 * debug build should show either, so every place that would otherwise print a driver id goes
 * through here instead.
 *
 * A pure `String? -> @StringRes Int` function on purpose: it is the one piece of this worth
 * testing, and a plain JVM unit test can call it.
 *
 * @return the friendly name for a known id, or the generic fallback for anything else (an unknown
 * id, a driver added later, or no device at all).
 */
@StringRes
fun driverNameRes(driverId: String?): Int = when (driverId) {
    "i4season-yuv" -> R.string.driver_name_yuv
    "useeplus" -> R.string.driver_name_mjpeg
    "uvc-bulk" -> R.string.driver_name_uvc
    else -> R.string.driver_name_unknown
}

/** @see driverNameRes */
@Composable
fun driverDisplayName(driverId: String?): String = stringResource(driverNameRes(driverId))
