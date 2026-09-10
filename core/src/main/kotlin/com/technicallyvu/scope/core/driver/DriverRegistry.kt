package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.i4season.I4seasonYuvDriver
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.core.uvc.UvcBulkDriver

/**
 * Every driver the app knows. Add a new device by adding a DeviceDriver here.
 *
 * Order matters: [find] takes the first match, so the vendor-specific drivers come first and the
 * generic [UvcBulkDriver] -- which matches on standard interface classes rather than a USB id --
 * stays last, as the fallback.
 */
object DriverRegistry {
    val all: List<DeviceDriver> = listOf(I4seasonYuvDriver(), UseeplusDriver(), UvcBulkDriver())

    fun find(info: UsbDeviceInfo): DeviceDriver? = all.firstOrNull { it.matches(info) }
}
