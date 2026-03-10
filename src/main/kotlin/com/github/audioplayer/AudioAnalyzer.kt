package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import javax.swing.ImageIcon

object AudioAnalyzer {

    private val LOG = Logger.getInstance(AudioAnalyzer::class.java)

    fun findFfmpeg(): String? {
        try {
            val process = ProcessBuilder("which", "ffmpeg")
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && result.isNotEmpty()) return result
        } catch (_: Exception) {}

        val paths = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
        for (path in paths) {
            if (File(path).exists()) return path
        }
        return null
    }

    fun buildWaveformCommand(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int
    ): List<String> {
        return listOf(
            ffmpegPath,
            "-i", inputPath,
            "-filter_complex", "showwavespic=s=${width}x${height}:colors=0x4488CC",
            "-frames:v", "1",
            "-y",
            outputPath
        )
    }

    fun buildSpectrumCommand(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int
    ): List<String> {
        return listOf(
            ffmpegPath,
            "-i", inputPath,
            "-filter_complex", "showspectrumpic=s=${width}x${height}",
            "-frames:v", "1",
            "-y",
            outputPath
        )
    }

    fun generateWaveform(file: File, width: Int, height: Int): ImageIcon? {
        return generateImage(file, width, height, ::buildWaveformCommand)
    }

    fun generateSpectrum(file: File, width: Int, height: Int): ImageIcon? {
        return generateImage(file, width, height, ::buildSpectrumCommand)
    }

    private fun generateImage(
        file: File,
        width: Int,
        height: Int,
        commandBuilder: (String, String, String, Int, Int) -> List<String>
    ): ImageIcon? {
        val ffmpeg = findFfmpeg() ?: run {
            LOG.warn("ffmpeg not found")
            return null
        }

        val outputFile = File.createTempFile("audioplayer_", ".png")
        outputFile.deleteOnExit()

        return try {
            val cmd = commandBuilder(ffmpeg, file.absolutePath, outputFile.absolutePath, width, height)
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText() // consume output
            val exitCode = process.waitFor()

            if (exitCode != 0 || !outputFile.exists() || outputFile.length() == 0L) {
                LOG.error("ffmpeg image generation failed with exit code $exitCode")
                outputFile.delete()
                return null
            }

            ImageIcon(outputFile.absolutePath)
        } catch (e: Exception) {
            LOG.error("Failed to generate image", e)
            outputFile.delete()
            null
        }
    }
}
