package com.technicallyvu.scope.desktop

import com.technicallyvu.scope.core.driver.DriverRegistry
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.PacketLogWriter
import com.technicallyvu.scope.core.i4season.I4seasonYuvDriver
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.desktop.usb.LibUsbDevices
import com.technicallyvu.scope.desktop.usb.WindowsDeviceCheck
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Usage:
 *   probe --list                                   show what libusb can see
 *   probe [--seconds N] [--record path.upkt]       stream from the first supported device
 */
fun main(args: Array<String>) {
    val secondsArg = argValue(args, "--seconds")
    val seconds = if (secondsArg == null) 10L else secondsArg.toLongOrNull()?.takeIf { it > 0 } ?: run {
        System.err.println("--seconds expects a positive whole number of seconds, got '$secondsArg'")
        exitProcess(2)
    }
    val record = argValue(args, "--record")?.let { Paths.get(it) }

    LibUsbDevices().use { devices ->
        val refs = devices.list()
        println("USB devices visible to libusb (${refs.size}):")
        for (r in refs) {
            val driver = DriverRegistry.find(r.info)
            val ifaces = r.info.interfaces.joinToString(" ") { "if%d:%02X/%02X/%02X".format(Locale.ROOT, it.number, it.usbClass, it.subclass, it.protocol) }
            println("  %s  bus %d addr %d  class 0x%02X  %s  %s".format(Locale.ROOT, r.info.idString, r.bus, r.address, r.info.usbClass, ifaces, driver?.let { "<- ${it.id}" } ?: ""))
        }
        if (args.contains("--list")) return

        val match = refs.firstNotNullOfOrNull { r -> DriverRegistry.find(r.info)?.let { r to it } }
        if (match == null) {
            val hint = if (WindowsDeviceCheck.isPresentWithoutDriver(I4seasonYuvDriver.SUPPORTED_IDS))
                "The endoscope is plugged in but has no WinUSB driver. Follow docs/windows-setup.md (Zadig)."
            else "No supported device found. Plug in the endoscope and try again."
            System.err.println(hint)
            exitProcess(2)
        }
        val (ref, matchedDriver) = match

        var packets = 0L
        val writer = record?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            PacketLogWriter(Files.newOutputStream(path))
        }
        val sink = PacketSink { p, len, ts ->
            packets++
            writer?.onPacket(p, len, ts)
        }

        val driver = matchedDriver.withPacketSink(sink)
        println("Opening ${ref.info.idString} with ${driver.id} ...")
        val transport = try {
            devices.open(ref)
        } catch (e: UsbException) {
            System.err.println("Could not open ${ref.info.idString}: ${e.message}")
            if (WindowsDeviceCheck.isPresentWithoutDriver(I4seasonYuvDriver.SUPPORTED_IDS)) {
                System.err.println("Windows has no WinUSB driver bound to the endoscope. Follow docs/windows-setup.md (Zadig), re-plug, and try again.")
            }
            exitProcess(2)
        }
        var frames = 0L
        var buttonFrames = 0L
        val source: FrameSource = try {
            driver.open(transport)
        } catch (e: UsbException) {
            System.err.println("Could not start ${driver.id}: ${e.message}")
            runCatching { transport.close() }
            runCatching { writer?.close() }
            exitProcess(2)
        }
        println("Streaming for $seconds s" + (record?.let { ", recording raw packets to $it" } ?: "") + ". Press the cable button a few times.")
        try {
            runBlocking {
                withTimeoutOrNull(seconds * 1000) {
                    var lastReport = System.nanoTime()
                    source.frames.collect { f ->
                        frames++
                        if (f.buttonPressed) buttonFrames++
                        val now = System.nanoTime()
                        if (now - lastReport >= 1_000_000_000L) {
                            val st = source.stats.value
                            val kind = when (val d = f.data) { is FrameData.Jpeg -> "jpeg ${d.bytes.size} B"; is FrameData.Yuyv422 -> "yuyv ${d.width}x${d.height}" }
                            println("  %.1f fps  frames=%d partial=%d dropped=%d bytes=%d  %s".format(Locale.ROOT, st.fps, st.framesEmitted, st.framesPartial, st.framesDropped, st.bytesReceived, kind))
                            lastReport = now
                        }
                    }
                }
            }
        } catch (e: UsbException) {
            // Unplug mid-stream (or a failed handshake) is expected here; no stack trace.
            println("Device removed after $packets packets (${e.message})")
        } finally {
            source.close()
            runCatching { writer?.close() }
        }
        println("Done. frames=$frames buttonFrames=$buttonFrames packets=$packets")
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
