package com.technicallyvu.scope.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

class RealUsbConnection(private val device: UsbDevice, private val connection: UsbDeviceConnection) : UsbConnection {
    private val endpoints: Map<Int, UsbEndpoint> = buildMap {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                putIfAbsent(ep.address, ep)
            }
        }
    }

    private fun iface(number: Int, alt: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.id == number && it.alternateSetting == alt) return it
        }
        return null
    }

    override fun claimInterface(number: Int): Boolean = iface(number, 0)?.let { connection.claimInterface(it, true) } ?: false
    override fun releaseInterface(number: Int): Boolean = iface(number, 0)?.let { connection.releaseInterface(it) } ?: false
    override fun setInterface(number: Int, alt: Int): Boolean = iface(number, alt)?.let { connection.setInterface(it) } ?: false

    override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        val ep = endpoints[endpoint] ?: return -1
        return connection.bulkTransfer(ep, buffer, length, timeoutMs)
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int =
        connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)

    override fun close() = connection.close()
}
