package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.useeplus.UseeplusDriver

/** Every driver the app knows. Add a new device by adding a DeviceDriver here. */
object DriverRegistry {
    val all: List<DeviceDriver> = listOf(UseeplusDriver())

    fun find(info: UsbDeviceInfo): DeviceDriver? = all.firstOrNull { it.matches(info) }
}
