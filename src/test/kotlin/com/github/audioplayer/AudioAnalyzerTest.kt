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
    fun `buildWaveformCommand adds seek duration and split channels`() {
        val cmd =
            AudioAnalyzer.buildWaveformCommand(
                ffmpegPath = "/bin/ffmpeg",
                inputPath = "/tmp/a.mp3",
                outputPath = "/tmp/w.png",
                width = 800,
                height = 200,
                startSec = 10.0,
                lenSec = 5.0,
                splitChannels = true,
            )
        val ss = cmd.indexOf("-ss")
        val t = cmd.indexOf("-t")
        val i = cmd.indexOf("-i")
        assertTrue(ss in 0 until i)
        assertTrue(t in 0 until i)
        assertEquals("10.0", cmd[ss + 1])
        assertEquals("5.0", cmd[t + 1])
        assertTrue(cmd.any { it.contains("split_channels=1") })
    }

    @Test
    fun `buildWaveformCommand without segment omits seek`() {
        val cmd =
            AudioAnalyzer.buildWaveformCommand("/bin/ffmpeg", "/tmp/a.mp3", "/tmp/w.png", 800, 200)
        assertFalse(cmd.contains("-ss"))
        assertFalse(cmd.contains("-t"))
        assertFalse(cmd.any { it.contains("split_channels") })
    }

    @Test
    fun `buildSpectrumCommand adds seek duration`() {
        val cmd =
            AudioAnalyzer.buildSpectrumCommand(
                "/bin/ffmpeg",
                "/tmp/a.mp3",
                "/tmp/s.png",
                800,
                200,
                startSec = 3.0,
                lenSec = 2.0,
            )
        assertEquals("3.0", cmd[cmd.indexOf("-ss") + 1])
        assertEquals("2.0", cmd[cmd.indexOf("-t") + 1])
    }
}
