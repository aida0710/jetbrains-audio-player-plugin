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
        startSec: Double? = null,
        lenSec: Double? = null,
        splitChannels: Boolean = false,
    ): List<String> {
        val split = if (splitChannels) ":split_channels=1" else ""
        return buildList {
            add(ffmpegPath)
            if (startSec != null) {
                add("-ss")
                add(startSec.toString())
            }
            if (lenSec != null) {
                add("-t")
                add(lenSec.toString())
            }
            add("-i")
            add(inputPath)
            add("-filter_complex")
            add("showwavespic=s=${width}x$height$split:colors=0x4488CC")
            add("-frames:v")
            add("1")
            add("-y")
            add(outputPath)
        }
    }

    fun buildSpectrumCommand(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        startSec: Double? = null,
        lenSec: Double? = null,
    ): List<String> =
        buildList {
            add(ffmpegPath)
            if (startSec != null) {
                add("-ss")
                add(startSec.toString())
            }
            if (lenSec != null) {
                add("-t")
                add(lenSec.toString())
            }
            add("-i")
            add(inputPath)
            add("-filter_complex")
            add("showspectrumpic=s=${width}x$height")
            add("-frames:v")
            add("1")
            add("-y")
            add(outputPath)
        }

    fun generateWaveformImage(
        file: File,
        width: Int,
        height: Int,
        startSec: Double? = null,
        lenSec: Double? = null,
        splitChannels: Boolean = false,
    ): BufferedImage? =
        generateImage(file, width, height) { ffmpeg, input, output, w, h ->
            buildWaveformCommand(ffmpeg, input, output, w, h, startSec, lenSec, splitChannels)
        }

    fun generateSpectrumImage(
        file: File,
        width: Int,
        height: Int,
        startSec: Double? = null,
        lenSec: Double? = null,
    ): BufferedImage? =
        generateImage(file, width, height) { ffmpeg, input, output, w, h ->
            buildSpectrumCommand(ffmpeg, input, output, w, h, startSec, lenSec)
        }

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
