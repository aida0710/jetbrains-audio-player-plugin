package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object LoudnessAnalyzer {
    private val LOG = Logger.getInstance(LoudnessAnalyzer::class.java)

    fun buildEbur128Command(
        ffmpeg: String,
        input: String,
    ): List<String> = listOf(ffmpeg, "-hide_banner", "-i", input, "-filter_complex", "ebur128", "-f", "null", "-")

    fun parseIntegratedLufs(output: String): String? {
        val matches = """I:\s*(-?\d+(?:\.\d+)?)\s*LUFS""".toRegex().findAll(output).toList()
        val value = matches.lastOrNull()?.groupValues?.get(1) ?: return null
        return "$value LUFS"
    }

    fun measure(file: File): String? {
        val ffmpeg = FfmpegPathUtil.findFfmpeg() ?: return null
        return try {
            val process =
                ProcessBuilder(buildEbur128Command(ffmpeg, file.absolutePath))
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            parseIntegratedLufs(output)
        } catch (e: Exception) {
            LOG.error("Loudness measurement failed", e)
            null
        }
    }
}
