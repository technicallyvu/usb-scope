package com.technicallyvu.scope.desktop

import com.technicallyvu.scope.core.driver.DriverRegistry
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.PacketLogWriter
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.core.useeplus.UseeplusPacket
import com.technicallyvu.scope.desktop.usb.LibUsbDevices
import com.technicallyvu.scope.desktop.usb.WindowsDeviceCheck
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Usage:
 *   probe --list                                   show what libusb can see
 *   probe [--seconds N] [--record path.upkt]       stream from the first supported device
 */
fun main(args: Array<String>) {
    val seconds = argValue(args, "--seconds")?.toLong() ?: 10L
    val record = argValue(args, "--record")?.let { Paths.get(it) }

    LibUsbDevices().use { devices ->
        val refs = devices.list()
        println("USB devices visible to libusb (${refs.size}):")
        for (r in refs) {
            val driver = DriverRegistry.find(r.info)
            println("  %s  bus %d addr %d  class 0x%02X  %s".format(r.info.idString, r.bus, r.address, r.info.usbClass, driver?.let { "<- ${it.id}" } ?: ""))
        }
        if (args.contains("--list")) return

        val ref = refs.firstOrNull { DriverRegistry.find(it.info) != null }
        if (ref == null) {
            val hint = if (WindowsDeviceCheck.isPresentWithoutDriver(UseeplusDriver.SUPPORTED_IDS))
                "The endoscope is plugged in but has no WinUSB driver. Follow docs/windows-setup.md (Zadig)."
            else "No supported device found. Plug in the endoscope and try again."
            System.err.println(hint)
            exitProcess(2)
        }

        val flagsHistogram = sortedMapOf<Int, Int>()
        var packets = 0L
        val writer = record?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            PacketLogWriter(Files.newOutputStream(path))
        }
        val sink = PacketSink { p, len, ts ->
            packets++
            UseeplusPacket.parse(p, len)?.let { c -> flagsHistogram.merge(c.flags, 1, Int::plus) }
            writer?.onPacket(p, len, ts)
        }

        val driver = UseeplusDriver(packetSink = sink)
        println("Opening ${ref.info.idString} with ${driver.id} ...")
        val transport = try {
            devices.open(ref)
        } catch (e: UsbException) {
            System.err.println("Could not open ${ref.info.idString}: ${e.message}")
            if (WindowsDeviceCheck.isPresentWithoutDriver(UseeplusDriver.SUPPORTED_IDS)) {
                System.err.println("Windows has no WinUSB driver bound to the endoscope. Follow docs/windows-setup.md (Zadig), re-plug, and try again.")
            }
            exitProcess(2)
        }
        val source = driver.open(transport)
        println("Streaming for $seconds s" + (record?.let { ", recording raw packets to $it" } ?: "") + ". Press the cable button a few times.")

        var frames = 0L
        var buttonFrames = 0L
        runBlocking {
            withTimeoutOrNull(seconds * 1000) {
                var lastReport = System.nanoTime()
                source.frames.collect { f ->
                    frames++
                    if (f.buttonPressed) buttonFrames++
                    val now = System.nanoTime()
                    if (now - lastReport >= 1_000_000_000L) {
                        val st = source.stats.value
                        println("  %.1f fps  frames=%d dropped=%d bytes=%d  jpeg=%d B".format(st.fps, st.framesEmitted, st.framesDropped, st.bytesReceived, f.jpeg.size))
                        lastReport = now
                    }
                }
            }
        }
        source.close()
        writer?.close()
        println("Done. frames=$frames buttonFrames=$buttonFrames packets=$packets")
        println("flags histogram (flags value -> packet count): $flagsHistogram")
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
