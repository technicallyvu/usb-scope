package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DriverRegistryTest {
    @Test
    fun `layout decides which driver claims the shared usb id`() {
        val yuv = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
        val jpeg = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 0), UsbInterfaceInfo(1, 0xFF, 0xF0, 1)))
        assertEquals("i4season-yuv", DriverRegistry.find(yuv)?.id)
        assertEquals("useeplus", DriverRegistry.find(jpeg)?.id)
        assertEquals("useeplus", DriverRegistry.find(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF))?.id)   // unknown layout
        assertNull(DriverRegistry.find(UsbDeviceInfo(0x046D, 0x0825, 0xEF)))
    }

    @Test
    fun `an unknown vendor with a video class layout falls through to the generic UVC driver`() {
        val uvc = UsbDeviceInfo(
            0x046D, 0x0825, 0xEF,
            listOf(UsbInterfaceInfo(0, 0x0E, 0x01, 0), UsbInterfaceInfo(1, 0x0E, 0x02, 0)),
        )
        assertEquals("uvc-bulk", DriverRegistry.find(uvc)?.id)

        // The vendor-specific drivers still win for the ids they know, UVC being last in the list.
        val yuv = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
        assertEquals("i4season-yuv", DriverRegistry.find(yuv)?.id)
    }
}
