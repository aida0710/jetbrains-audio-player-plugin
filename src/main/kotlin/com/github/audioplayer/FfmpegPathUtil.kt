package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object FfmpegPathUtil {
    private val LOG = Logger.getInstance(FfmpegPathUtil::class.java)

    private val FFMPEG_SEARCH_PATHS =
        listOf(
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg",
        )

    private val FFPROBE_SEARCH_PATHS =
        listOf(
            "/opt/homebrew/bin/ffprobe",
            "/usr/local/bin/ffprobe",
            "/usr/bin/ffprobe",
        )

    fun autoDetectFfmpeg(): String? = autoDetect("ffmpeg", FFMPEG_SEARCH_PATHS)

    fun autoDetectFfprobe(): String? = autoDetect("ffprobe", FFPROBE_SEARCH_PATHS)

    private fun autoDetect(
        command: String,
        searchPaths: List<String>,
    ): String? {
        // PATHから`which`で検索
        try {
            val process =
                ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start()
            val result =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            if (process.waitFor() == 0 && result.isNotEmpty()) {
                LOG.info("Found $command via PATH: $result")
                return result
            }
        } catch (e: Exception) {
            LOG.info("'which $command' failed: ${e.message}")
        }

        // 既知のパスから検索
        for (path in searchPaths) {
            if (File(path).exists()) {
                LOG.info("Found $command at known path: $path")
                return path
            }
        }

        LOG.warn("$command not found")
        return null
    }

    fun findFfmpeg(): String? {
        val settings =
            try {
                AudioPlayerSettings.instance
            } catch (_: Exception) {
                null
            }
        val configured = settings?.state?.ffmpegPath.orEmpty()
        if (configured.isNotEmpty()) {
            return configured
        }
        return autoDetectFfmpeg()
    }

    fun findFfprobe(): String? {
        val settings =
            try {
                AudioPlayerSettings.instance
            } catch (_: Exception) {
                null
            }
        val configured = settings?.state?.ffprobePath.orEmpty()
        if (configured.isNotEmpty()) {
            return configured
        }
        return autoDetectFfprobe()
    }

    fun testExecutable(path: String): Boolean {
        if (path.isEmpty()) return false
        return try {
            val process =
                ProcessBuilder(path, "-version")
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
