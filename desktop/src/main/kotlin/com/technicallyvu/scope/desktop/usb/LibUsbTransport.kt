package com.technicallyvu.scope.desktop.usb

import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import org.usb4java.BufferUtils
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import java.nio.ByteBuffer
import java.nio.IntBuffer

/** libusb-backed transport. Requires WinUSB bound to the device on Windows (docs/windows-setup.md). */
class LibUsbTransport(private val handle: DeviceHandle) : UsbTransport {
    private var readBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    private val transferred: IntBuffer = BufferUtils.allocateIntBuffer()

    override fun claimInterface(iface: Int) = check(LibUsb.claimInterface(handle, iface), "claimInterface $iface")
    override fun releaseInterface(iface: Int) = check(LibUsb.releaseInterface(handle, iface), "releaseInterface $iface")
    override fun setAltSetting(iface: Int, alt: Int) = check(LibUsb.setInterfaceAltSetting(handle, iface, alt), "setAltSetting $iface/$alt")
    override fun clearHalt(endpoint: Int) = check(LibUsb.clearHalt(handle, endpoint.toByte()), "clearHalt %02X".format(endpoint))

    override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val buf = ByteBuffer.allocateDirect(data.size)
        buf.put(data)
        buf.rewind()
        transferred.clear()
        check(LibUsb.bulkTransfer(handle, endpoint.toByte(), buf, transferred, timeoutMs.toLong()), "bulkWrite %02X".format(endpoint))
        return transferred.get(0)
    }

    override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        if (readBuffer.capacity() != buffer.size) readBuffer = ByteBuffer.allocateDirect(buffer.size)
        readBuffer.clear()
        transferred.clear()
        val r = LibUsb.bulkTransfer(handle, endpoint.toByte(), readBuffer, transferred, timeoutMs.toLong())
        if (r != LibUsb.SUCCESS && r != LibUsb.ERROR_TIMEOUT) {
            throw UsbException("bulkRead %02X failed: %s".format(endpoint, LibUsb.strError(r)), r)
        }
        val n = minOf(transferred.get(0), buffer.size)
        if (n > 0) {
            readBuffer.rewind()
            readBuffer.get(buffer, 0, n)
        }
        return n
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val buf = ByteBuffer.allocateDirect(data.size)
        if (requestType and 0x80 == 0) {
            buf.put(data)
            buf.rewind()
        }
        val r = LibUsb.controlTransfer(handle, requestType.toByte(), request.toByte(), value.toShort(), index.toShort(), buf, timeoutMs.toLong())
        if (r < 0) throw UsbException("controlTransfer %02X/%02X failed: %s".format(requestType, request, LibUsb.strError(r)), r)
        if (requestType and 0x80 != 0 && r > 0) {
            buf.rewind()
            buf.get(data, 0, r)
        }
        return r
    }

    override fun resetDevice() = check(LibUsb.resetDevice(handle), "resetDevice")

    override fun close() {
        LibUsb.close(handle)
    }

    private fun check(code: Int, op: String) {
        if (code != LibUsb.SUCCESS) throw UsbException("$op failed: ${LibUsb.strError(code)}", code)
    }
}
