package com.technicallyvu.scope.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the cable-button navigation rule. The rest of the button path (the haptic tick, the flash
 * tick, the recorder handshake) needs a view model and a Compose harness, neither of which this
 * module has — but the rule that makes the path *safe* is a pure function of the destination, so it
 * can be pinned here: a clip must never be written while the user is looking at a screen that
 * cannot show the REC badge.
 */
class ScreenAfterButtonEventTest {

    @Test
    fun `a button event from any screen lands on the live view`() {
        Screen.entries.forEach { from ->
            assertEquals(Screen.Live, screenAfterButtonEvent(from), "from $from")
        }
    }

    @Test
    fun `a button event on the live view is a no-op, so it cannot churn the screen state`() {
        assertEquals(Screen.Live, screenAfterButtonEvent(Screen.Live))
    }
}
