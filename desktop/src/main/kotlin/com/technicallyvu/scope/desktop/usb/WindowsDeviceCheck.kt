package com.technicallyvu.scope.desktop.usb

import java.util.concurrent.TimeUnit

/** Detects "endoscope is plugged in but Windows has no driver bound" via pnputil. */
object WindowsDeviceCheck {
    fun isWindows(): Boolean = System.getProperty("os.name", "").startsWith("Windows")

    /** True when pnputil lists a connected, problem-state device with one of [ids]. Never throws. */
    fun isPresentWithoutDriver(ids: Set<Pair<Int, Int>>): Boolean {
        if (!isWindows()) return false
        return try {
            val proc = ProcessBuilder("pnputil", "/enum-devices", "/connected", "/problem")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)
            parse(output, ids)
        } catch (e: Exception) {
            false
        }
    }

    fun parse(pnputilOutput: String, ids: Set<Pair<Int, Int>>): Boolean =
        ids.any { (vid, pid) -> pnputilOutput.contains("VID_%04X&PID_%04X".format(vid, pid), ignoreCase = true) }
}
