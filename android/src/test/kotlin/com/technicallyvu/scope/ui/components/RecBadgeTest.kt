package com.technicallyvu.scope.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The REC badge's elapsed-time formatter. Pure Kotlin (no Compose, no Android), so it runs as a
 * plain JVM unit test even though it lives beside the composable that uses it.
 */
class RecBadgeTest {

    @Test
    fun `zero reads as zero minutes and seconds`() {
        assertEquals("00:00", formatElapsed(0L))
    }

    @Test
    fun `seconds are zero padded`() {
        assertEquals("00:12", formatElapsed(12L * 1_000_000_000L))
    }

    @Test
    fun `just under an hour still reads in minutes`() {
        assertEquals("59:59", formatElapsed(3599L * 1_000_000_000L))
    }

    /** Minutes are never wrapped at 60: a long clip must not read "00:00" again after an hour. */
    @Test
    fun `an hour keeps counting minutes`() {
        assertEquals("60:00", formatElapsed(3600L * 1_000_000_000L))
    }

    /** A clock that ran backwards (or a badge drawn a hair before its start stamp) shows zero. */
    @Test
    fun `negative elapsed clamps to zero`() {
        assertEquals("00:00", formatElapsed(-5L * 1_000_000_000L))
    }

    /** Part-seconds round down, so the badge shows "00:00" for the first whole second. */
    @Test
    fun `sub second elapsed rounds down`() {
        assertEquals("00:00", formatElapsed(999_000_000L))
    }
}
