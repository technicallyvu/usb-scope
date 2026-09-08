package com.technicallyvu.scope.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun `toolchain runs kotlin tests`() {
        assertEquals(4, 2 + 2)
    }
}
