package com.technicallyvu.scope.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.util.concurrent.ConcurrentHashMap

class RealUsbConnection(private val device: UsbDevice, private val connection: UsbDeviceConnection) : UsbConnection {
    /**
     * Alternate setting currently selected per interface id (default 0). [UsbDevice] lists every
     * alternate setting as its own [UsbInterface], and different alternate settings may reuse the
     * same endpoint address, so an endpoint address alone does not identify an endpoint. Written on
     * the session thread, read on the IO thread, hence the concurrent map.
     */
    private val selectedAlt = ConcurrentHashMap<Int, Int>()

    private fun iface(number: Int, alt: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.id == number && it.alternateSetting == alt) return it
        }
        return null
    }

    /**
     * Resolves [address] against the alternate settings actually selected, falling back to any
     * interface that carries the address (e.g. before the interface has been claimed).
     * Throws [IllegalArgumentException] when no interface has that endpoint at all, so a wiring
     * mistake surfaces as an error rather than looking like a transfer timeout.
     */
    private fun endpointFor(address: Int): UsbEndpoint {
        var fallback: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.address != address) continue
                if (iface.alternateSetting == (selectedAlt[iface.id] ?: 0)) return ep
                if (fallback == null) fallback = ep
            }
        }
        return fallback ?: throw IllegalArgumentException("no endpoint %02X".format(address))
    }

    override fun claimInterface(number: Int): Boolean =
        iface(number, 0)?.let { connection.claimInterface(it, true).also { ok -> if (ok) selectedAlt[number] = 0 } } ?: false

    override fun releaseInterface(number: Int): Boolean = iface(number, 0)?.let { connection.releaseInterface(it) } ?: false

    override fun setInterface(number: Int, alt: Int): Boolean =
        iface(number, alt)?.let { connection.setInterface(it).also { ok -> if (ok) selectedAlt[number] = alt } } ?: false

    override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(endpointFor(endpoint), buffer, length, timeoutMs)

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int =
        connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)

    override fun close() = connection.close()
}
