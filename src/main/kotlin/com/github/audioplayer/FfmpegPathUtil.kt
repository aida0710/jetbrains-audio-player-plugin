package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object FfmpegPathUtil {
    private val LOG = Logger.getInstance(FfmpegPathUtil::class.java)

    fun isWindowsOs(osName: String): Boolean = osName.lowercase().startsWith("win")

    fun locateCommand(windows: Boolean): String = if (windows) "where" else "which"

    fun candidatePaths(
        command: String,
        windows: Boolean,
        env: (String) -> String?,
    ): List<String> =
        if (!windows) {
            listOf(
                "/opt/homebrew/bin/$command",
                "/usr/local/bin/$command",
                "/usr/bin/$command",
            )
        } else {
            val exe = "$command.exe"
            buildList {
                env("LOCALAPPDATA")?.let { add("$it\\Microsoft\\WinGet\\Links\\$exe") }
                env("USERPROFILE")?.let { add("$it\\scoop\\shims\\$exe") }
                add("C:\\ProgramData\\chocolatey\\bin\\$exe")
                add("C:\\ffmpeg\\bin\\$exe")
                add("${env("ProgramFiles") ?: "C:\\Program Files"}\\ffmpeg\\bin\\$exe")
            }
        }

    fun autoDetectFfmpeg(): String? = autoDetect("ffmpeg")

    fun autoDetectFfprobe(): String? = autoDetect("ffprobe")

    private fun autoDetect(command: String): String? {
        val windows = isWindowsOs(System.getProperty("os.name").orEmpty())
        val locate = locateCommand(windows)

        // PATH から where/which で検索
        try {
            val process =
                ProcessBuilder(locate, command)
                    .redirectErrorStream(true)
                    .start()
            val result =
                process.inputStream.bufferedReader().use { reader ->
                    reader
                        .lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                }
            if (process.waitFor() == 0 && result.isNotEmpty()) {
                LOG.info("Found $command via $locate: $result")
                return result
            }
        } catch (e: Exception) {
            LOG.info("'$locate $command' failed: ${e.message}")
        }

        // 既知のパスから検索
        for (path in candidatePaths(command, windows) { System.getenv(it) }) {
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
