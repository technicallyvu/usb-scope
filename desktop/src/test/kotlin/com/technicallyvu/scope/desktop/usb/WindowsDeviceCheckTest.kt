package com.technicallyvu.scope.desktop.usb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowsDeviceCheckTest {
    private val sample = """
        Microsoft PnP Utility

        Instance ID:                USB\VID_2CE3&PID_3828\202402062300000
        Device Description:         supercamera
        Class Name:
        Class GUID:
        Manufacturer Name:
        Status:                     Problem
        Problem Code:               28
        Driver Name:
    """.trimIndent()

    private val ids = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)

    @Test
    fun `finds a known device in pnputil output`() {
        assertTrue(WindowsDeviceCheck.parse(sample, ids))
    }

    @Test
    fun `ignores output without the device`() {
        assertFalse(WindowsDeviceCheck.parse("Instance ID: USB\\VID_0B05&PID_1A27\\5&F34C116&0&7", ids))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(WindowsDeviceCheck.parse("usb\\vid_0329&pid_2022\\x", ids))
    }
}
