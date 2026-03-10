package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioFileEditorProviderTest {
    @Test
    fun `getEditorTypeId returns consistent id`() {
        val provider = AudioFileEditorProvider()
        assertEquals("audio-player-editor", provider.editorTypeId)
    }
}
