package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class LoudnessAnalyzerTest {
    @Test
    fun `buildEbur128Command builds correct command`() {
        val cmd = LoudnessAnalyzer.buildEbur128Command("/bin/ffmpeg", "/tmp/a.mp3")
        assertEquals("/bin/ffmpeg", cmd[0])
        assertEquals("/tmp/a.mp3", cmd[cmd.indexOf("-i") + 1])
        assertTrue(cmd.contains("ebur128"))
        assertEquals("-", cmd.last())
    }

    @Test
    fun `parseIntegratedLufs takes the last I value`() {
        val output =
            """
            [Parsed_ebur128_0 @ 0x1] t: 1   M: -20.0 S:-120.7 I: -19.0 LUFS
            [Parsed_ebur128_0 @ 0x1] Summary:
              Integrated loudness:
                I:         -14.2 LUFS
                Threshold: -24.7 LUFS
            """.trimIndent()
        assertEquals("-14.2 LUFS", LoudnessAnalyzer.parseIntegratedLufs(output))
    }

    @Test
    fun `parseIntegratedLufs returns null when absent`() {
        assertNull(LoudnessAnalyzer.parseIntegratedLufs("no loudness here"))
    }
}
