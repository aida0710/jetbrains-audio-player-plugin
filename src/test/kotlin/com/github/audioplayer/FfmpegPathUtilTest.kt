package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FfmpegPathUtilTest {
    @Test
    fun `autoDetectFfmpeg returns path when ffmpeg is installed`() {
        val path = FfmpegPathUtil.autoDetectFfmpeg()
        assertNotNull("ffmpeg not found on PATH", path)
        assertTrue(File(path!!).exists())
    }

    @Test
    fun `autoDetectFfprobe returns path when ffprobe is installed`() {
        val path = FfmpegPathUtil.autoDetectFfprobe()
        assertNotNull("ffprobe not found on PATH", path)
        assertTrue(File(path!!).exists())
    }

    @Test
    fun `testExecutable returns true for valid ffmpeg path`() {
        val path = FfmpegPathUtil.autoDetectFfmpeg() ?: return
        assertTrue(FfmpegPathUtil.testExecutable(path))
    }

    @Test
    fun `testExecutable returns false for invalid path`() {
        assertFalse(FfmpegPathUtil.testExecutable("/nonexistent/path/ffmpeg"))
    }

    @Test
    fun `testExecutable returns false for empty path`() {
        assertFalse(FfmpegPathUtil.testExecutable(""))
    }
}
