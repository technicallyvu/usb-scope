package com.technicallyvu.scope.desktop.usb

import java.util.concurrent.TimeUnit

/** Detects "endoscope is plugged in but Windows has no driver bound" via pnputil. */
object WindowsDeviceCheck {
    private const val PROCESS_TIMEOUT_SECONDS = 5L
    /** The UI polls at 1 Hz; pnputil takes hundreds of ms, so answer from cache in between. */
    private const val CACHE_MILLIS = 20_000L

    @Volatile private var cachedResult = false
    @Volatile private var cachedAtMillis = Long.MIN_VALUE

    fun isWindows(): Boolean = System.getProperty("os.name", "").startsWith("Windows")

    /**
     * True when pnputil lists a connected, problem-state device with one of [ids]. Never throws.
     * The answer is cached for 20 s so a 1 Hz poll does not spawn a process every second.
     */
    fun isPresentWithoutDriver(ids: Set<Pair<Int, Int>>): Boolean {
        if (!isWindows()) return false
        val now = System.currentTimeMillis()
        val since = now - cachedAtMillis
        if (since in 0 until CACHE_MILLIS) return cachedResult
        val result = query(ids)
        cachedResult = result
        cachedAtMillis = now
        return result
    }

    /** Runs pnputil with a bounded wait, reading stdout on a helper thread so the pipe cannot block us. */
    private fun query(ids: Set<Pair<Int, Int>>): Boolean = try {
        val proc = ProcessBuilder("pnputil", "/enum-devices", "/connected", "/problem")
            .redirectErrorStream(true)
            .start()
        var output = ""
        val reader = Thread({ output = runCatching { proc.inputStream.readAllBytes().decodeToString() }.getOrDefault("") },
            "pnputil-reader")
        reader.isDaemon = true
        reader.start()
        if (!proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) proc.destroyForcibly()
        reader.join(TimeUnit.SECONDS.toMillis(PROCESS_TIMEOUT_SECONDS))
        parse(output, ids)
    } catch (e: Exception) {
        false
    }

    fun parse(pnputilOutput: String, ids: Set<Pair<Int, Int>>): Boolean =
        ids.any { (vid, pid) -> pnputilOutput.contains("VID_%04X&PID_%04X".format(vid, pid), ignoreCase = true) }
}
