package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.UseeplusDriver

/**
 * A [UsbTransport] that answers video bulk reads from a recorded packet list. Control-in reads
 * report "nothing pending" (0). Every other call is recorded in [calls] so tests can assert the
 * exact handshake. Doubles as the hardware-free `--replay` mode for the desktop app.
 */
class ReplayTransport(
    private val packets: List<LoggedPacket>,
    private val loop: Boolean = false,
    private val sleep: (millis: Long) -> Unit = {},
) : UsbTransport {
    val calls = mutableListOf<String>()

    /** Number of times [claimInterface] should throw before succeeding (retry tests). */
    var failuresBeforeSuccess = 0
    var resets = 0
        private set

    private var index = 0
    private var lastTimestamp = -1L

    override fun claimInterface(iface: Int) {
        calls += "claim $iface"
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            throw UsbException("simulated claim failure")
        }
    }

    override fun releaseInterface(iface: Int) { calls += "release $iface" }
    override fun setAltSetting(iface: Int, alt: Int) { calls += "alt $iface $alt" }
    override fun clearHalt(endpoint: Int) { calls += "clearHalt ${hex(endpoint)}" }

    override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        calls += "write ${hex(endpoint)} " + data.joinToString(" ") { "%02X".format(it) }
        return data.size
    }

    override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        if (endpoint != UseeplusDriver.EP_VIDEO_IN) return 0
        if (index >= packets.size) {
            if (!loop || packets.isEmpty()) throw UsbException("replay ended")
            index = 0
            lastTimestamp = -1
        }
        val p = packets[index++]
        if (lastTimestamp >= 0 && p.timestampNanos > lastTimestamp) {
            sleep((p.timestampNanos - lastTimestamp) / 1_000_000)
        }
        lastTimestamp = p.timestampNanos
        val n = minOf(p.bytes.size, buffer.size)
        p.bytes.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun resetDevice() { resets++; calls += "reset" }
    override fun close() { calls += "close" }

    private fun hex(v: Int) = "%02X".format(v and 0xFF)
}
