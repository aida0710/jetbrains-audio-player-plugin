package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioPlayerServiceTest {
    @Test
    fun `formatTime formats zero seconds`() {
        assertEquals("00:00", AudioPlayerService.formatTime(0))
    }

    @Test
    fun `formatTime formats seconds only`() {
        assertEquals("00:45", AudioPlayerService.formatTime(45))
    }

    @Test
    fun `formatTime formats minutes and seconds`() {
        assertEquals("01:05", AudioPlayerService.formatTime(65))
    }

    @Test
    fun `formatTime formats exact minutes`() {
        assertEquals("10:00", AudioPlayerService.formatTime(600))
    }

    @Test
    fun `formatTime formats hours`() {
        assertEquals("1:00:00", AudioPlayerService.formatTime(3600))
    }

    @Test
    fun `formatTime formats hours minutes seconds`() {
        assertEquals("1:23:45", AudioPlayerService.formatTime(5025))
    }

    @Test
    fun `initial state is STOPPED`() {
        val service = AudioPlayerService()
        assertEquals(AudioPlayerService.PlaybackState.STOPPED, service.state)
    }

    @Test
    fun `play without load does not crash`() {
        val service = AudioPlayerService()
        service.play()
        assertEquals(AudioPlayerService.PlaybackState.STOPPED, service.state)
    }

    @Test
    fun `stop without load does not crash`() {
        val service = AudioPlayerService()
        service.stop()
        assertEquals(AudioPlayerService.PlaybackState.STOPPED, service.state)
    }

    @Test
    fun `pause without load does not crash`() {
        val service = AudioPlayerService()
        service.pause()
        assertEquals(AudioPlayerService.PlaybackState.STOPPED, service.state)
    }

    @Test
    fun `seek without load does not crash`() {
        val service = AudioPlayerService()
        service.seek(1000)
    }

    @Test
    fun `setVolume without load does not crash`() {
        val service = AudioPlayerService()
        service.setVolume(50f)
    }

    @Test
    fun `setLooping without load does not crash`() {
        val service = AudioPlayerService()
        service.setLooping(true)
    }

    @Test
    fun `dispose without load does not crash`() {
        val service = AudioPlayerService()
        service.dispose()
        assertEquals(AudioPlayerService.PlaybackState.STOPPED, service.state)
    }

    @Test
    fun `totalMicroseconds is 0 without load`() {
        val service = AudioPlayerService()
        assertEquals(0L, service.totalMicroseconds)
    }

    @Test
    fun `currentMicroseconds is 0 without load`() {
        val service = AudioPlayerService()
        assertEquals(0L, service.currentMicroseconds)
    }

    @Test
    fun `computeSeekTarget adds positive delta`() {
        assertEquals(15_000_000L, AudioPlayerService.computeSeekTarget(10_000_000L, 5_000_000L, 60_000_000L))
    }

    @Test
    fun `computeSeekTarget clamps to zero on negative result`() {
        assertEquals(0L, AudioPlayerService.computeSeekTarget(3_000_000L, -5_000_000L, 60_000_000L))
    }

    @Test
    fun `computeSeekTarget clamps to total on overflow`() {
        assertEquals(60_000_000L, AudioPlayerService.computeSeekTarget(58_000_000L, 5_000_000L, 60_000_000L))
    }

    @Test
    fun `computeSeekTarget returns zero when total is zero`() {
        assertEquals(0L, AudioPlayerService.computeSeekTarget(0L, 5_000_000L, 0L))
    }

    @Test
    fun `computePeak returns max abs normalized`() {
        assertEquals(1.0f, AudioPlayerService.computePeak(shortArrayOf(0, 16384, -32768)), 0.001f)
        assertEquals(0.5f, AudioPlayerService.computePeak(shortArrayOf(16384)), 0.01f)
        assertEquals(0.0f, AudioPlayerService.computePeak(shortArrayOf()), 0.0f)
    }

    @Test
    fun `computeRms returns normalized rms`() {
        assertEquals(0.0f, AudioPlayerService.computeRms(shortArrayOf(0, 0)), 0.0f)
        assertEquals(1.0f, AudioPlayerService.computeRms(shortArrayOf(32767, -32767)), 0.01f)
        assertEquals(0.0f, AudioPlayerService.computeRms(shortArrayOf()), 0.0f)
    }

    @Test
    fun `renderedToOriginalMicros scales by speed`() {
        assertEquals(20_000_000L, AudioPlayerService.renderedToOriginalMicros(10_000_000L, 2.0f))
        assertEquals(10_000_000L, AudioPlayerService.renderedToOriginalMicros(10_000_000L, 1.0f))
    }

    @Test
    fun `originalToRenderedMicros divides by speed`() {
        assertEquals(10_000_000L, AudioPlayerService.originalToRenderedMicros(20_000_000L, 2.0f))
        assertEquals(20_000_000L, AudioPlayerService.originalToRenderedMicros(10_000_000L, 0.5f))
    }

    @Test
    fun `bytesToShorts converts little-endian 16-bit`() {
        // 0x0000=0, 0x0100=256 (LE), 0xFFFF=-1
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xFF.toByte(), 0xFF.toByte())
        val shorts = AudioPlayerService.bytesToShorts(bytes, 6)
        assertEquals(0.toShort(), shorts[0])
        assertEquals(256.toShort(), shorts[1])
        assertEquals((-1).toShort(), shorts[2])
    }

    @Test
    fun `shouldLoopBack true when current reaches B`() {
        assertTrue(AudioPlayerService.shouldLoopBack(4_000_000, 1_000_000, 4_000_000))
        assertTrue(AudioPlayerService.shouldLoopBack(5_000_000, 1_000_000, 4_000_000))
    }

    @Test
    fun `shouldLoopBack false before B or invalid range`() {
        assertFalse(AudioPlayerService.shouldLoopBack(3_000_000, 1_000_000, 4_000_000))
        assertFalse(AudioPlayerService.shouldLoopBack(5_000_000, -1, 4_000_000))
        assertFalse(AudioPlayerService.shouldLoopBack(5_000_000, 4_000_000, 4_000_000))
    }

    @Test
    fun `shouldLoopBack true when A is zero`() {
        assertTrue(AudioPlayerService.shouldLoopBack(4_000_000, 0, 4_000_000))
    }
}
