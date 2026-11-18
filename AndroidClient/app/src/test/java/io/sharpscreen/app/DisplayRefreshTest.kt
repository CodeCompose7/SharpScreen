package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRefreshTest {
    // The Tab S11 Ultra's modes as SurfaceFlinger lists them.
    private val modes =
        listOf(
            DisplayRefresh.Mode(0, 2960, 1848, 120f),
            DisplayRefresh.Mode(1, 2960, 1848, 96f),
            DisplayRefresh.Mode(2, 2960, 1848, 60f),
            DisplayRefresh.Mode(3, 2960, 1848, 48f),
            DisplayRefresh.Mode(4, 2960, 1848, 30f),
        )

    @Test
    fun picks120WhenAdaptiveRefreshDroppedTo96() {
        assertEquals(0, DisplayRefresh.highestRefreshMode(modes, modes[1])?.id)
    }

    @Test
    fun keepsTheResolution() {
        val withOtherSize = modes + DisplayRefresh.Mode(9, 1480, 924, 144f)
        assertEquals(0, DisplayRefresh.highestRefreshMode(withOtherSize, modes[2])?.id)
    }

    @Test
    fun nothingBetterThanTheTopMode() {
        assertNull(DisplayRefresh.highestRefreshMode(modes, modes[0]))
    }
}
