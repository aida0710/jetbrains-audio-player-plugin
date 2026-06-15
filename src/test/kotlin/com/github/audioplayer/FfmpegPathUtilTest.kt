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

    @Test
    fun `isWindowsOs detects windows`() {
        assertTrue(FfmpegPathUtil.isWindowsOs("Windows 11"))
        assertTrue(FfmpegPathUtil.isWindowsOs("windows 10"))
    }

    @Test
    fun `isWindowsOs false for unix`() {
        assertFalse(FfmpegPathUtil.isWindowsOs("Mac OS X"))
        assertFalse(FfmpegPathUtil.isWindowsOs("Linux"))
    }

    @Test
    fun `locateCommand picks where on windows and which otherwise`() {
        assertEquals("where", FfmpegPathUtil.locateCommand(true))
        assertEquals("which", FfmpegPathUtil.locateCommand(false))
    }

    @Test
    fun `candidatePaths returns unix paths`() {
        val paths = FfmpegPathUtil.candidatePaths("ffmpeg", false) { null }
        assertEquals(
            listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"),
            paths,
        )
    }

    @Test
    fun `candidatePaths windows with env expands all`() {
        val env =
            mapOf(
                "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
                "USERPROFILE" to "C:\\Users\\me",
                "ProgramFiles" to "C:\\Program Files",
            )
        val paths = FfmpegPathUtil.candidatePaths("ffmpeg", true) { env[it] }
        assertEquals(
            listOf(
                "C:\\Users\\me\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe",
                "C:\\Users\\me\\scoop\\shims\\ffmpeg.exe",
                "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
            ),
            paths,
        )
    }

    @Test
    fun `candidatePaths windows skips missing env and defaults ProgramFiles`() {
        val paths = FfmpegPathUtil.candidatePaths("ffprobe", true) { null }
        assertEquals(
            listOf(
                "C:\\ProgramData\\chocolatey\\bin\\ffprobe.exe",
                "C:\\ffmpeg\\bin\\ffprobe.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffprobe.exe",
            ),
            paths,
        )
    }
}
