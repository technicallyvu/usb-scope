package com.technicallyvu.scope.core.usb

data class DeviceRef(val info: UsbDeviceInfo, val bus: Int, val address: Int)

/** Enumerates attached USB devices and opens one. One implementation per platform. */
interface DeviceSource {
    fun list(): List<DeviceRef>
    fun open(ref: DeviceRef): UsbTransport
}
