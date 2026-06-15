package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object AudioAnalyzer {
    private val LOG = Logger.getInstance(AudioAnalyzer::class.java)

    fun buildWaveformCommand(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
    ): List<String> =
        listOf(
            ffmpegPath,
            "-i",
            inputPath,
            "-filter_complex",
            "showwavespic=s=${width}x$height:colors=0x4488CC",
            "-frames:v",
            "1",
            "-y",
            outputPath,
        )

    fun buildSpectrumCommand(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
    ): List<String> =
        listOf(
            ffmpegPath,
            "-i",
            inputPath,
            "-filter_complex",
            "showspectrumpic=s=${width}x$height",
            "-frames:v",
            "1",
            "-y",
            outputPath,
        )

    fun generateWaveformImage(
        file: File,
        width: Int,
        height: Int,
    ): BufferedImage? = generateImage(file, width, height, ::buildWaveformCommand)

    fun generateSpectrumImage(
        file: File,
        width: Int,
        height: Int,
    ): BufferedImage? = generateImage(file, width, height, ::buildSpectrumCommand)

    private fun generateImage(
        file: File,
        width: Int,
        height: Int,
        commandBuilder: (String, String, String, Int, Int) -> List<String>,
    ): BufferedImage? {
        val ffmpeg =
            FfmpegPathUtil.findFfmpeg() ?: run {
                LOG.warn("ffmpeg not found")
                return null
            }

        var tempFile: File? = null
        return try {
            val outputFile = File.createTempFile("audioplayer_", ".png")
            tempFile = outputFile
            val cmd = commandBuilder(ffmpeg, file.absolutePath, outputFile.absolutePath, width, height)
            val process =
                ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().readText() // consume output
            val exitCode = process.waitFor()

            if (exitCode != 0 || !outputFile.exists() || outputFile.length() == 0L) {
                LOG.error("ffmpeg image generation failed with exit code $exitCode")
                return null
            }

            ImageIO.read(outputFile).also {
                if (it == null) LOG.error("ImageIO.read returned null for ${outputFile.absolutePath}")
            }
        } catch (e: Exception) {
            LOG.error("Failed to generate image", e)
            null
        } finally {
            tempFile?.delete()
        }
    }
}
