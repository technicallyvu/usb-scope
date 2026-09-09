package com.technicallyvu.scope.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import java.util.concurrent.ConcurrentHashMap

// core.usb.UsbException is not `open`, so it cannot be subclassed here; UsbPermissionException
// extends Exception directly instead (deviation from the brief, recorded in the task report).
class UsbPermissionException(val device: UsbDevice) : Exception("USB permission not granted for ${device.deviceName}")

/** [DeviceSource] over [UsbManager]. `DeviceRef.address` carries Android's deviceId. */
class AndroidDeviceSource(private val usbManager: UsbManager) : DeviceSource {
    private val openTransports = ConcurrentHashMap<String, AndroidUsbTransport>()

    /** Set when [open] found a device we lack permission for; the shell requests permission and clears it. */
    @Volatile var pendingPermission: UsbDevice? = null

    override fun list(): List<DeviceRef> = usbManager.deviceList.values.map { d -> DeviceRef(info(d), bus = 0, address = d.deviceId) }

    override fun open(ref: DeviceRef): UsbTransport {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == ref.address } ?: throw UsbException("device ${ref.info.idString} is gone")
        if (!usbManager.hasPermission(device)) {
            pendingPermission = device
            throw UsbPermissionException(device)
        }
        pendingPermission = null
        val connection = usbManager.openDevice(device) ?: throw UsbException("openDevice returned null for ${device.deviceName}")
        val transport = AndroidUsbTransport(RealUsbConnection(device, connection))
        openTransports[device.deviceName] = transport
        return transport
    }

    /** Called from the ACTION_USB_DEVICE_DETACHED receiver so an in-flight read fails fast. */
    fun onDetached(deviceName: String) {
        openTransports.remove(deviceName)?.detached = true
    }

    companion object {
        fun info(d: UsbDevice): UsbDeviceInfo {
            val interfaces = (0 until d.interfaceCount).map { d.getInterface(it) }
                .filter { it.alternateSetting == 0 }
                .map { UsbInterfaceInfo(it.id, it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
            return UsbDeviceInfo(d.vendorId, d.productId, d.deviceClass, interfaces)
        }
    }
}
