package com.technicallyvu.scope.desktop.usb

import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceHandle
import org.usb4java.DeviceList
import org.usb4java.LibUsb

/**
 * Enumerates devices via libusb. On Windows libusb only lists devices with WinUSB/libusbK bound,
 * so an unbound endoscope is simply absent here (see [WindowsDeviceCheck]).
 */
class LibUsbDevices : DeviceSource, AutoCloseable {
    private val context = Context()

    init {
        val r = LibUsb.init(context)
        if (r != LibUsb.SUCCESS) throw UsbException("libusb init failed: ${LibUsb.strError(r)}", r)
    }

    override fun list(): List<DeviceRef> = withDeviceList { list ->
        list.map { dev -> DeviceRef(describe(dev), LibUsb.getBusNumber(dev).toInt(), LibUsb.getDeviceAddress(dev).toInt()) }
    }

    override fun open(ref: DeviceRef): UsbTransport = withDeviceList { list ->
        val dev = list.firstOrNull {
            LibUsb.getBusNumber(it).toInt() == ref.bus && LibUsb.getDeviceAddress(it).toInt() == ref.address
        } ?: throw UsbException("device ${ref.info.idString} is no longer attached")
        val handle = DeviceHandle()
        val r = LibUsb.open(dev, handle)
        if (r != LibUsb.SUCCESS) throw UsbException("open ${ref.info.idString} failed: ${LibUsb.strError(r)}", r)
        LibUsbTransport(handle)
    }

    override fun close() {
        LibUsb.exit(context)
    }

    private fun describe(dev: Device): UsbDeviceInfo {
        val d = DeviceDescriptor()
        LibUsb.getDeviceDescriptor(dev, d)
        return UsbDeviceInfo(
            vendorId = d.idVendor().toInt() and 0xFFFF,
            productId = d.idProduct().toInt() and 0xFFFF,
            usbClass = d.bDeviceClass().toInt() and 0xFF,
        )
    }

    private fun <T> withDeviceList(block: (DeviceList) -> T): T {
        val list = DeviceList()
        val r = LibUsb.getDeviceList(context, list)
        if (r < 0) throw UsbException("getDeviceList failed: ${LibUsb.strError(r)}", r)
        try {
            return block(list)
        } finally {
            LibUsb.freeDeviceList(list, true)   // an open handle keeps its device alive
        }
    }
}
