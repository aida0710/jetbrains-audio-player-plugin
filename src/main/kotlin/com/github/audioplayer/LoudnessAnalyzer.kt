package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

object LoudnessAnalyzer {
    private val LOG = Logger.getInstance(LoudnessAnalyzer::class.java)
    private val LUFS_REGEX = """I:\s*(-?\d+(?:\.\d+)?)\s*LUFS""".toRegex()

    fun buildEbur128Command(
        ffmpeg: String,
        input: String,
    ): List<String> = listOf(ffmpeg, "-hide_banner", "-i", input, "-filter_complex", "ebur128", "-f", "null", "-")

    fun parseIntegratedLufs(output: String): String? {
        val value =
            LUFS_REGEX
                .findAll(output)
                .lastOrNull()
                ?.groupValues
                ?.get(1) ?: return null
        return "$value LUFS"
    }

    fun measure(file: File): String? {
        val ffmpeg = FfmpegPathUtil.findFfmpeg() ?: return null
        return try {
            val process =
                ProcessBuilder(buildEbur128Command(ffmpeg, file.absolutePath))
                    .redirectErrorStream(true)
                    .start()
            val output = StringBuilder()
            val reader =
                Thread {
                    process.inputStream.bufferedReader().use { r -> r.forEachLine { output.appendLine(it) } }
                }
            reader.start()
            if (!process.waitFor(300, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                LOG.warn("ebur128 timed out for ${file.name}")
                return null
            }
            reader.join(2000)
            parseIntegratedLufs(output.toString())
        } catch (e: Exception) {
            LOG.error("Loudness measurement failed", e)
            null
        }
    }
}
