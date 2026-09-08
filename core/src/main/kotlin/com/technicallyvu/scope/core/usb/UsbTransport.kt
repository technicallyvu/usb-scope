package com.technicallyvu.scope.core.usb

class UsbException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

data class UsbDeviceInfo(val vendorId: Int, val productId: Int, val usbClass: Int) {
    val idString: String get() = "%04X:%04X".format(vendorId, productId)
}

/** The five USB primitives a driver needs. One implementation per platform. */
interface UsbTransport : AutoCloseable {
    fun claimInterface(iface: Int)
    fun releaseInterface(iface: Int)
    fun setAltSetting(iface: Int, alt: Int)
    fun clearHalt(endpoint: Int)
    /** Returns bytes written. Throws [UsbException] on error. */
    fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    /** Returns bytes read; 0 on timeout. Throws [UsbException] on any other error, including device removal. */
    fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
    fun resetDevice()
}
