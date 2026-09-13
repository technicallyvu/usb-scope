package com.technicallyvu.scope.ui

import com.technicallyvu.scope.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The driver-id → display-name mapping. A pure function over resource ids, so a plain JVM test can
 * assert it without a device: what matters here is that every id the registry can produce resolves
 * to its own friendly name, and that anything else lands on the generic fallback rather than
 * showing the raw id.
 */
class DriverNamesTest {

    @Test
    fun `known driver ids map to their own names`() {
        assertEquals(R.string.driver_name_yuv, driverNameRes("i4season-yuv"))
        assertEquals(R.string.driver_name_mjpeg, driverNameRes("useeplus"))
        assertEquals(R.string.driver_name_uvc, driverNameRes("uvc-bulk"))
    }

    @Test
    fun `an unknown id falls back to the generic name`() {
        assertEquals(R.string.driver_name_unknown, driverNameRes("something-else"))
    }

    @Test
    fun `no driver at all falls back to the generic name`() {
        assertEquals(R.string.driver_name_unknown, driverNameRes(null))
        assertEquals(R.string.driver_name_unknown, driverNameRes(""))
    }

    @Test
    fun `the mapping is not accidentally collapsed onto one string`() {
        val ids = listOf("i4season-yuv", "useeplus", "uvc-bulk", "nope").map(::driverNameRes)
        assertEquals(ids.size, ids.toSet().size, "each driver id needs its own display name")
    }

    @Test
    fun `case matters - a differently spelled id is not silently accepted`() {
        assertNotEquals(R.string.driver_name_mjpeg, driverNameRes("Useeplus"))
    }
}
