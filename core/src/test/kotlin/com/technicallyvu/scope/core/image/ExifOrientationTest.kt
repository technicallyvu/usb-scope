package com.technicallyvu.scope.core.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExifOrientationTest {
    @Test
    fun `table matches the EXIF spec`() {
        assertEquals(1, ExifOrientation.of(0, false)); assertEquals(2, ExifOrientation.of(0, true))
        assertEquals(6, ExifOrientation.of(90, false)); assertEquals(7, ExifOrientation.of(90, true))
        assertEquals(3, ExifOrientation.of(180, false)); assertEquals(4, ExifOrientation.of(180, true))
        assertEquals(8, ExifOrientation.of(270, false)); assertEquals(5, ExifOrientation.of(270, true))
        assertEquals(6, ExifOrientation.of(450, false)); assertEquals(8, ExifOrientation.of(-90, false))
    }
}
