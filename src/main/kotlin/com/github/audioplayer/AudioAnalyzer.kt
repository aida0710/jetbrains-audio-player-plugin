package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon

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

    fun generateWaveform(
        file: File,
        width: Int,
        height: Int,
    ): ImageIcon? = generateWaveformImage(file, width, height)?.let { ImageIcon(it) }

    fun generateSpectrum(
        file: File,
        width: Int,
        height: Int,
    ): ImageIcon? = generateSpectrumImage(file, width, height)?.let { ImageIcon(it) }

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

        val outputFile = File.createTempFile("audioplayer_", ".png")

        return try {
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

            ImageIO.read(outputFile)
        } catch (e: Exception) {
            LOG.error("Failed to generate image", e)
            null
        } finally {
            outputFile.delete()
        }
    }
}
