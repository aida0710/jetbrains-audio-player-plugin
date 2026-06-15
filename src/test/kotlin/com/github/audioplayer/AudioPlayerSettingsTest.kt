package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioPlayerSettingsTest {
    @Test
    fun `default lastVolume is 80`() {
        assertEquals(80, AudioPlayerSettings.SettingsState().lastVolume)
    }

    @Test
    fun `default lastLooping is false`() {
        assertFalse(AudioPlayerSettings.SettingsState().lastLooping)
    }

    @Test
    fun `default defaultView is waveform`() {
        assertEquals("waveform", AudioPlayerSettings.SettingsState().defaultView)
    }

    @Test
    fun `default showVisualizer is true`() {
        assertTrue(AudioPlayerSettings.SettingsState().showVisualizer)
    }
}
