package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioPlayerPanelInfoTest {
    @Test
    fun `infoRowsToText joins rows as tab-separated lines`() {
        val text =
            AudioPlayerPanel.infoRowsToText(
                listOf("File" to "a.mp3", "Duration" to "01:00"),
            )
        assertEquals("File\ta.mp3\nDuration\t01:00", text)
    }

    @Test
    fun `defaultImageFileName composes base and view`() {
        assertEquals("song_waveform.png", AudioPlayerPanel.defaultImageFileName("song", "waveform"))
        assertEquals("song_spectrum.png", AudioPlayerPanel.defaultImageFileName("song", "spectrum"))
    }
}
