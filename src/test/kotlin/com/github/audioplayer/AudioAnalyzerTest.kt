package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioAnalyzerTest {
    @Test
    fun `buildWaveformCommand constructs correct ffmpeg command`() {
        val cmd =
            AudioAnalyzer.buildWaveformCommand(
                ffmpegPath = "/usr/local/bin/ffmpeg",
                inputPath = "/tmp/test.mp3",
                outputPath = "/tmp/waveform.png",
                width = 800,
                height = 200,
            )

        assertEquals("/usr/local/bin/ffmpeg", cmd[0])
        assertTrue(cmd.contains("-i"))
        assertEquals("/tmp/test.mp3", cmd[cmd.indexOf("-i") + 1])
        assertTrue(cmd.any { it.contains("showwavespic") })
        assertTrue(cmd.any { it.contains("s=800x200") })
        assertEquals("/tmp/waveform.png", cmd.last())
        assertTrue(cmd.contains("-y"))
    }

    @Test
    fun `buildSpectrumCommand constructs correct ffmpeg command`() {
        val cmd =
            AudioAnalyzer.buildSpectrumCommand(
                ffmpegPath = "/usr/local/bin/ffmpeg",
                inputPath = "/tmp/test.mp3",
                outputPath = "/tmp/spectrum.png",
                width = 800,
                height = 200,
            )

        assertEquals("/usr/local/bin/ffmpeg", cmd[0])
        assertTrue(cmd.contains("-i"))
        assertEquals("/tmp/test.mp3", cmd[cmd.indexOf("-i") + 1])
        assertTrue(cmd.any { it.contains("showspectrumpic") })
        assertTrue(cmd.any { it.contains("s=800x200") })
        assertEquals("/tmp/spectrum.png", cmd.last())
        assertTrue(cmd.contains("-y"))
    }

    @Test
    fun `findFfmpeg returns path when ffmpeg is installed`() {
        val path = AudioAnalyzer.findFfmpeg()
        assertNotNull("ffmpeg not found on PATH", path)
    }
}
