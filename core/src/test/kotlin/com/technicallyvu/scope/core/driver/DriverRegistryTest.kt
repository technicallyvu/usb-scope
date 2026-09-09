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
}
