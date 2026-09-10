package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.fixture.ReplayTransport
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UvcProbeTest {

    @Test
    fun `struct length follows the uvc version`() {
        assertEquals(26, UvcProbe.lengthFor(0x0100))
        assertEquals(26, UvcProbe.lengthFor(0x0010))
        assertEquals(34, UvcProbe.lengthFor(0x0110))
        assertEquals(34, UvcProbe.lengthFor(0x0140))
        assertEquals(48, UvcProbe.lengthFor(0x0150))
        assertEquals(48, UvcProbe.lengthFor(0x0500))
    }

    @Test
    fun `encode and decode round trip at every struct length`() {
        val control = UvcProbeControl(
            formatIndex = 2,
            frameIndex = 3,
            frameInterval100ns = 333_333,
            maxVideoFrameSize = 614_400,
            maxPayloadTransferSize = 16_384,
        )
        for (length in listOf(26, 34, 48)) {
            val bytes = UvcProbe.encode(control, length)
            assertEquals(length, bytes.size, "encoded length")
            // bmHint = 0x0001, little endian: "keep dwFrameInterval fixed".
            assertEquals(0x01, bytes[0].toInt() and 0xFF)
            assertEquals(0x00, bytes[1].toInt() and 0xFF)
            // Everything past dwMaxPayloadTransferSize stays zero.
            for (i in 26 until length) assertEquals(0, bytes[i].toInt(), "byte $i of $length")
            assertEquals(control, UvcProbe.decode(bytes, length))
        }
    }

    @Test
    fun `decode reads only the fields a short reply actually covers`() {
        val control = UvcProbeControl(1, 1, 333_333, 614_400, 16_384)
        val short = UvcProbe.decode(UvcProbe.encode(control, 34), 8)
        assertEquals(1, short.formatIndex)
        assertEquals(1, short.frameIndex)
        assertEquals(333_333, short.frameInterval100ns)
        assertEquals(0, short.maxVideoFrameSize)
        assertEquals(0, short.maxPayloadTransferSize)
    }

    @Test
    fun `negotiate is probe SET_CUR, probe GET_CUR, commit SET_CUR and commits the reply verbatim`() {
        val t = ReplayTransport(emptyList())
        val fromDevice = UvcProbeControl(1, 1, 333_333, 614_400, 16_384)
        val reply = UvcProbe.encode(fromDevice, 34)
        // A field past dwMaxPayloadTransferSize that decode() does not model (bmFramingInfo and the
        // rest of the 1.1 tail). Re-encoding the decoded struct would commit a zero here.
        reply[30] = 0x5A

        t.controlResponses[0xA1 to 0x81] = reply

        val negotiated = UvcProbe.negotiate(
            t,
            vsInterface = 1,
            bcdUvc = 0x0110,
            request = UvcProbeControl(1, 1, 333_333, 614_400, 0),
        )

        assertEquals(fromDevice, negotiated)
        assertEquals(
            listOf(
                "ctrl 21 01 0100 0001 34",
                "ctrl A1 81 0100 0001 34",
                "ctrl 21 01 0200 0001 34",
            ),
            t.calls,
        )
        assertArrayEquals(reply, t.lastControlOut, "the commit must be the device's own reply bytes")
    }

    @Test
    fun `a reply shorter than the struct is committed padded, not re-encoded`() {
        val t = ReplayTransport(emptyList())
        val fromDevice = UvcProbeControl(2, 3, 333_333, 614_400, 8_192)
        val reply = UvcProbe.encode(fromDevice, 26)   // a 1.0-length answer to a 1.1-length request
        reply[12] = 0x77                              // wCompQuality: unmodelled, inside what was returned
        t.controlResponses[0xA1 to 0x81] = reply

        val negotiated = UvcProbe.negotiate(
            t,
            vsInterface = 1,
            bcdUvc = 0x0110,
            request = UvcProbeControl(1, 1, 333_333, 614_400, 0),
        )

        assertEquals(fromDevice, negotiated)
        val commit = requireNotNull(t.lastControlOut)
        assertEquals(34, commit.size, "the commit keeps the struct length the device's version implies")
        assertArrayEquals(reply, commit.copyOf(26), "the returned bytes survive unchanged")
        for (i in 26 until 34) assertEquals(0, commit[i].toInt(), "byte $i past the reply must be zero padding")
    }

    @Test
    fun `a device that answers the probe GET_CUR with nothing keeps our requested values`() {
        val t = ReplayTransport(emptyList())
        val request = UvcProbeControl(1, 1, 333_333, 614_400, 0)
        assertEquals(request, UvcProbe.negotiate(t, vsInterface = 1, bcdUvc = 0x0100, request = request))
        assertEquals(3, t.calls.size)
        assertEquals("ctrl 21 01 0200 0001 26", t.calls.last())
        // Nothing usable came back, so our own request is what gets committed.
        assertArrayEquals(UvcProbe.encode(request, 26), t.lastControlOut)
    }
}
