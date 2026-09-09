package com.technicallyvu.scope.core.usb

class UsbException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/** One interface of the active configuration (alternate setting 0). */
data class UsbInterfaceInfo(val number: Int, val usbClass: Int, val subclass: Int, val protocol: Int)

data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val usbClass: Int,
    /** Interfaces of the active configuration, or empty when the platform could not read them. */
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
) {
    val idString: String get() = "%04X:%04X".format(vendorId, productId)
}

/** The USB primitives a driver needs. One implementation per platform. */
interface UsbTransport : AutoCloseable {
    fun claimInterface(iface: Int)
    fun releaseInterface(iface: Int)
    fun setAltSetting(iface: Int, alt: Int)
    fun clearHalt(endpoint: Int)
    /** Returns bytes written. Throws [UsbException] on error. */
    fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    /** Returns bytes read; 0 on timeout. Throws [UsbException] on any other error, including device removal. */
    fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
    /**
     * Control transfer on endpoint 0. For device-to-host requests (bit 7 of [requestType] set) [data]
     * is filled and the number of bytes received is returned; for host-to-device requests [data] is
     * sent and the number of bytes sent is returned. Throws [UsbException] on error (including STALL).
     */
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int
    fun resetDevice()
}
