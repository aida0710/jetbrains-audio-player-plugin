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
}
