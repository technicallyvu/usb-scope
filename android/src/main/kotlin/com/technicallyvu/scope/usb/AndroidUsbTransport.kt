package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport

/**
 * [UsbTransport] over the Android USB Host API. Android reports both timeout and error as -1 from
 * bulkTransfer, so "device gone" is derived from the [detached] flag (set from ACTION_USB_DEVICE_DETACHED)
 * plus a limit on consecutive empty reads.
 */
class AndroidUsbTransport(
    private val conn: UsbConnection,
    private val maxConsecutiveTimeouts: Int = 20,
) : UsbTransport {
    @Volatile var detached: Boolean = false
    private var consecutiveTimeouts = 0

    override fun claimInterface(iface: Int) {
        if (!conn.claimInterface(iface)) throw UsbException("claimInterface $iface failed")
    }

    override fun releaseInterface(iface: Int) {
        conn.releaseInterface(iface)
    }

    override fun setAltSetting(iface: Int, alt: Int) {
        if (!conn.setInterface(iface, alt)) throw UsbException("setAltSetting $iface/$alt failed")
    }

    override fun clearHalt(endpoint: Int) {
        // Standard request: bmRequestType 0x02 (host-to-device, standard, endpoint), CLEAR_FEATURE (1), ENDPOINT_HALT (0), wIndex = endpoint
        val r = conn.controlTransfer(0x02, 0x01, 0x00, endpoint, null, 0, 1000)
        if (r < 0) throw UsbException("clearHalt %02X failed".format(endpoint))
    }

    override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = conn.bulkTransfer(endpoint, data, data.size, timeoutMs)
        if (n < 0) throw UsbException("bulkWrite %02X failed".format(endpoint))
        return n
    }

    override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        if (detached) throw UsbException("device detached")
        val n = conn.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        if (n >= 0) {
            consecutiveTimeouts = 0
            return n
        }
        if (detached) throw UsbException("device detached")
        consecutiveTimeouts++
        if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
            throw UsbException("no data after $maxConsecutiveTimeouts reads; assuming the device is gone")
        }
        return 0
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val r = conn.controlTransfer(requestType, request, value, index, data, data.size, timeoutMs)
        if (r < 0) throw UsbException("controlTransfer %02X/%02X failed".format(requestType, request))
        return r
    }

    override fun resetDevice() {
        throw UsbException("resetDevice is not available on Android")
    }

    override fun close() = conn.close()
}
