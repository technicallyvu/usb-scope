package com.technicallyvu.scope.core.useeplus

/** Frames per second over a sliding time window of frame timestamps. */
class FpsMeter(private val windowNanos: Long = 2_000_000_000L) {
    private val stamps = ArrayDeque<Long>()

    fun tick(nowNanos: Long): Double {
        stamps.addLast(nowNanos)
        while (stamps.size > 1 && nowNanos - stamps.first() > windowNanos) stamps.removeFirst()
        if (stamps.size < 2) return 0.0
        val span = stamps.last() - stamps.first()
        return if (span <= 0) 0.0 else (stamps.size - 1) * 1e9 / span
    }
}
