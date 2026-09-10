package com.technicallyvu.scope.core.usb

import java.util.Locale

open class UsbException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/** One interface of the active configuration (alternate setting 0). */
data class UsbInterfaceInfo(val number: Int, val usbClass: Int, val subclass: Int, val protocol: Int)

data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val usbClass: Int,
    /** Interfaces of the active configuration, or empty when the platform could not read them. */
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
) {
    val idString: String get() = "%04X:%04X".format(Locale.ROOT, vendorId, productId)
}

/** The USB primitives a driver needs. One implementation per platform. */
interface UsbTransport : AutoCloseable {
    fun claimInterface(iface: Int)
    fun releaseInterface(iface: Int)
    /**
     * Selects an alternate setting. Android: `UsbDeviceConnection.setInterface(usbInterface)` with the
     * [android.hardware.usb.UsbInterface] whose `getAlternateSetting()` equals [alt].
     */
    fun setAltSetting(iface: Int, alt: Int)
    /**
     * Clears a halted endpoint. Android has no direct call: issue a CLEAR_FEATURE control transfer
     * (requestType 0x02, request 0x01, value 0x00 = ENDPOINT_HALT, index = [endpoint]).
     */
    fun clearHalt(endpoint: Int)
    /** Returns bytes written. Throws [UsbException] on error. */
    fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    /**
     * Returns bytes read; 0 on timeout. Throws [UsbException] on any other error, including device removal.
     *
     * Android note for the Phase 2 transport: `UsbDeviceConnection.bulkTransfer` returns -1 for BOTH a
     * timeout and a real error, so it cannot distinguish "nothing yet" from "device gone". Pair a short
     * timeout (see each driver's READ_TIMEOUT_MS) with a separate liveness check — e.g. a device-list
     * lookup or an ACTION_USB_DEVICE_DETACHED receiver — and only then map -1 to a [UsbException].
     */
    fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
    /**
     * Control transfer on endpoint 0. For device-to-host requests (bit 7 of [requestType] set) [data]
     * is filled and the number of bytes received is returned; for host-to-device requests [data] is
     * sent and the number of bytes sent is returned. Throws [UsbException] on error (including STALL).
     */
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int
    /**
     * USB port reset. **Not used by any driver**: on libusb a reset invalidates the device handle, so it
     * cannot be used inside an open-retry loop, and Android's `UsbDeviceConnection` has no equivalent.
     * Kept for the desktop probe and manual debugging only; a platform without it may throw.
     */
    fun resetDevice()
}
