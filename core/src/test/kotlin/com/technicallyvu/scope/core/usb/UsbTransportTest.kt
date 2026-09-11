package com.technicallyvu.scope.core.usb

import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.uvc.UvcTestDescriptors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UsbTransportTest {

    @Test
    fun `readConfigDescriptor reads the 9-byte header then wTotalLength bytes`() {
        val cfg = UvcTestDescriptors.build()
        val t = ReplayTransport(emptyList()).apply { controlResponses[0x80 to 0x06] = cfg }

        assertArrayEquals(cfg, t.readConfigDescriptor())
        assertEquals(
            listOf("ctrl 80 06 0200 0000 9", "ctrl 80 06 0200 0000 ${cfg.size}"),
            t.calls,
        )
    }

    @Test
    fun `a device that answers nothing is an error, not an empty descriptor`() {
        val t = ReplayTransport(emptyList())
        assertThrows(UsbException::class.java) { t.readConfigDescriptor() }
    }
}
