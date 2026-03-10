package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object AudioConverter {
    private val LOG = Logger.getInstance(AudioConverter::class.java)

    private val SUPPORTED_EXTENSIONS =
        setOf(
            "mp3",
            "wav",
            "ogg",
            "flac",
            "aac",
            "m4a",
            "wma",
            "opus",
            "ape",
            "aiff",
            "aif",
        )

    fun isSupportedExtension(extension: String?): Boolean = extension?.lowercase() in SUPPORTED_EXTENSIONS

    fun convertToWav(sourceFile: File): File? {
        LOG.info("convertToWav called for: ${sourceFile.absolutePath}, exists=${sourceFile.exists()}")
        if (!sourceFile.exists()) {
            LOG.warn("Source file does not exist: ${sourceFile.absolutePath}")
            return null
        }

        val extension = sourceFile.extension.lowercase()
        if (extension == "wav") {
            return try {
                javax.sound.sampled.AudioSystem
                    .getAudioInputStream(sourceFile)
                LOG.info("WAV file is directly playable")
                sourceFile
            } catch (e: Exception) {
                LOG.info("WAV not directly playable, converting: ${e.message}")
                convertWithFfmpeg(sourceFile)
            }
        }

        return convertWithFfmpeg(sourceFile)
    }

    private fun convertWithFfmpeg(sourceFile: File): File? {
        val ffmpeg = FfmpegPathUtil.findFfmpeg()
        if (ffmpeg == null) {
            LOG.error("ffmpeg not found. Please install ffmpeg (e.g. brew install ffmpeg)")
            return null
        }

        return try {
            val targetFile = File.createTempFile("audioplayer_", ".wav")
            targetFile.deleteOnExit()

            LOG.info("Converting with ffmpeg: ${sourceFile.name} -> ${targetFile.name}")
            val process =
                ProcessBuilder(
                    ffmpeg,
                    "-i",
                    sourceFile.absolutePath,
                    "-acodec",
                    "pcm_s16le",
                    "-ar",
                    "44100",
                    "-ac",
                    "2",
                    "-y",
                    targetFile.absolutePath,
                ).redirectErrorStream(true)
                    .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                LOG.error("ffmpeg exited with code $exitCode: $output")
                targetFile.delete()
                return null
            }

            LOG.info("ffmpeg conversion complete. Output size: ${targetFile.length()} bytes")
            targetFile
        } catch (e: Exception) {
            LOG.error("ffmpeg conversion failed", e)
            null
        }
    }
}
