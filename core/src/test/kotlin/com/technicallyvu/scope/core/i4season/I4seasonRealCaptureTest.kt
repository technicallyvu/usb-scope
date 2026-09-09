package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.PacketLogReader
import com.technicallyvu.scope.core.fixture.ReplayTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Real-capture regression tests against a 2.54 s fixture recorded from Anthony's unit
 * (270 bulk reads, 28 frame headers, 3 button presses, 5 short/padded frames).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I4seasonRealCaptureTest {
    private lateinit var packets: List<LoggedPacket>

    @BeforeAll
    fun load() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/i4season-yuv-320x240.upkt")) { "fixture missing; run Task 17" }
        packets = stream.use { PacketLogReader.read(it) }
        assertTrue(packets.size > 50, "capture too short: ${packets.size} reads")
    }

    /**
     * Replays the fixture through the real driver/parser. [paced] replays at the recorded
     * inter-packet spacing (via [Thread.sleep]) so the driver's default `System::nanoTime` clock
     * stamps frames with realistic timing; unpaced (the default) replays instantly, which is fine
     * for every assertion except frame rate.
     */
    private fun capture(paced: Boolean = false): Pair<List<Frame>, StreamStats> = runBlocking {
        val sleepFn: (Long) -> Unit = if (paced) Thread::sleep else { _ -> }
        val t = ReplayTransport(packets, sleep = sleepFn, videoEndpoint = I4seasonYuvDriver.EP_IN)
        t.controlResponses[0xA0 to 0x00] = I4seasonTestFrames.realInfo()
        val source = I4seasonYuvDriver(ioDispatcher = Dispatchers.Default).open(t)
        // Collect into an externally-owned list: Flow.toList() only returns its result on normal
        // completion, so once the replay runs out of packets and the flow throws, a plain
        // `runCatching { source.frames.toList() }` would discard every frame collected so far.
        val collected = mutableListOf<Frame>()
        runCatching { source.frames.collect { collected.add(it) } }
        val stats = source.stats.value
        source.close()
        collected to stats
    }

    @Test
    fun `at least 20 frames of 320x240 yuyv`() {
        val (frames, _) = capture()
        assertTrue(frames.size >= 20, "only ${frames.size} frames")
        for (f in frames) {
            val d = f.data as FrameData.Yuyv422
            assertEquals(320, d.width); assertEquals(240, d.height); assertEquals(153600, d.bytes.size)
        }
    }

    @Test
    fun `chroma bytes cluster around 128 and luma is not constant`() {
        val (frames, _) = capture()
        val d = frames.last().data as FrameData.Yuyv422
        val chroma = (1 until d.bytes.size step 2).map { d.bytes[it].toInt() and 0xFF }
        val chromaMean = chroma.average()
        assertTrue(chromaMean in 100.0..156.0, "chroma mean $chromaMean; wrong byte order?")
        val luma = (0 until d.bytes.size step 2).map { d.bytes[it].toInt() and 0xFF }
        assertTrue(luma.distinct().size > 16, "luma looks constant; not a picture")
    }

    @Test
    fun `frame rate from timestamps is between 5 and 30 fps`() {
        val (frames, _) = capture(paced = true)
        val span = (frames.last().timestampNanos - frames.first().timestampNanos) / 1e9
        val fps = (frames.size - 1) / span
        assertTrue(fps in 5.0..30.0, "fps $fps")
    }

    @Test
    fun `a button press appears in the capture`() {
        val (frames, _) = capture()
        assertTrue(frames.any { it.buttonPressed }, "no buttonPressed frame; recapture while pressing the button")
    }

    @Test
    fun `short frames are padded and counted as partial`() {
        val (frames, stats) = capture()
        assertTrue(stats.framesPartial >= 1, "expected at least one padded short frame, got ${stats.framesPartial}")
        for (f in frames) {
            val d = f.data as FrameData.Yuyv422
            assertEquals(153600, d.bytes.size)
        }
    }
}
