package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class TimelineImagePanelTest {
    @Test
    fun `timeAtX maps window edges and midpoint`() {
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(0, 1000, 10_000_000, 20_000_000))
        assertEquals(20_000_000L, TimelineImagePanel.timeAtX(1000, 1000, 10_000_000, 20_000_000))
        assertEquals(15_000_000L, TimelineImagePanel.timeAtX(500, 1000, 10_000_000, 20_000_000))
    }

    @Test
    fun `timeAtX clamps x and guards bad window`() {
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(-10, 1000, 10_000_000, 20_000_000))
        assertEquals(20_000_000L, TimelineImagePanel.timeAtX(5000, 1000, 10_000_000, 20_000_000))
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(500, 0, 10_000_000, 20_000_000))
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(500, 1000, 10_000_000, 10_000_000))
    }

    @Test
    fun `xAtTime maps within window without clamping`() {
        assertEquals(0, TimelineImagePanel.xAtTime(10_000_000, 1000, 10_000_000, 20_000_000))
        assertEquals(1000, TimelineImagePanel.xAtTime(20_000_000, 1000, 10_000_000, 20_000_000))
        assertEquals(500, TimelineImagePanel.xAtTime(15_000_000, 1000, 10_000_000, 20_000_000))
        assertEquals(-500, TimelineImagePanel.xAtTime(5_000_000, 1000, 10_000_000, 20_000_000))
    }

    @Test
    fun `zoomWindow zooms in around anchor and clamps`() {
        val (s, e) = TimelineImagePanel.zoomWindow(0, 60_000_000, 0.5, 0.5, 60_000_000)
        assertEquals(15_000_000L, s)
        assertEquals(45_000_000L, e)
    }

    @Test
    fun `zoomWindow out clamps to full duration`() {
        val (s, e) = TimelineImagePanel.zoomWindow(15_000_000, 45_000_000, 2.0, 0.5, 60_000_000)
        assertEquals(0L, s)
        assertEquals(60_000_000L, e)
    }

    @Test
    fun `zoomWindow respects minimum width`() {
        val (s, e) = TimelineImagePanel.zoomWindow(0, 60_000_000, 0.0001, 0.0, 60_000_000)
        assertEquals(100_000L, e - s)
    }

    @Test
    fun `panWindow shifts and clamps`() {
        assertEquals(
            15_000_000L to 25_000_000L,
            TimelineImagePanel.panWindow(10_000_000, 20_000_000, 5_000_000, 60_000_000),
        )
        assertEquals(
            50_000_000L to 60_000_000L,
            TimelineImagePanel.panWindow(55_000_000, 65_000_000, 5_000_000, 60_000_000),
        )
        assertEquals(0L to 10_000_000L, TimelineImagePanel.panWindow(5_000_000, 15_000_000, -10_000_000, 60_000_000))
    }

    @Test
    fun `computeTicks produces nice 10s interval for full minute`() {
        val ticks = TimelineImagePanel.computeTicks(0, 60_000_000, 800, 80)
        assertEquals("00:00", ticks.first().label)
        assertEquals("00:10", ticks[1].label)
        assertTrue(ticks.size >= 6)
        assertTrue(ticks[1].xPixel > ticks[0].xPixel)
    }

    @Test
    fun `computeTicks empty for bad input`() {
        assertTrue(TimelineImagePanel.computeTicks(0, 0, 800, 80).isEmpty())
        assertTrue(TimelineImagePanel.computeTicks(0, 60_000_000, 0, 80).isEmpty())
    }
}
