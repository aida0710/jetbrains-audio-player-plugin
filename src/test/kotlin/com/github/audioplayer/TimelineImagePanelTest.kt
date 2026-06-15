package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class TimelineImagePanelTest {
    private val duration = 60_000_000L

    @Test
    fun `timeAtX maps left edge to zero`() {
        assertEquals(0L, TimelineImagePanel.timeAtX(0, 1000, duration))
    }

    @Test
    fun `timeAtX maps right edge to duration`() {
        assertEquals(duration, TimelineImagePanel.timeAtX(1000, 1000, duration))
    }

    @Test
    fun `timeAtX maps midpoint to half duration`() {
        assertEquals(30_000_000L, TimelineImagePanel.timeAtX(500, 1000, duration))
    }

    @Test
    fun `timeAtX clamps negative x to zero`() {
        assertEquals(0L, TimelineImagePanel.timeAtX(-50, 1000, duration))
    }

    @Test
    fun `timeAtX clamps x beyond width to duration`() {
        assertEquals(duration, TimelineImagePanel.timeAtX(2000, 1000, duration))
    }

    @Test
    fun `timeAtX returns zero when width is zero`() {
        assertEquals(0L, TimelineImagePanel.timeAtX(500, 0, duration))
    }

    @Test
    fun `xAtTime maps zero to left edge`() {
        assertEquals(0, TimelineImagePanel.xAtTime(0, 1000, duration))
    }

    @Test
    fun `xAtTime maps midpoint`() {
        assertEquals(500, TimelineImagePanel.xAtTime(30_000_000L, 1000, duration))
    }

    @Test
    fun `xAtTime clamps full duration to last pixel`() {
        assertEquals(999, TimelineImagePanel.xAtTime(duration, 1000, duration))
    }

    @Test
    fun `xAtTime returns zero when duration is zero`() {
        assertEquals(0, TimelineImagePanel.xAtTime(100, 1000, 0))
    }
}
