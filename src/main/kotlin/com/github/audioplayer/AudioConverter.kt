package com.github.audioplayer

import ws.schild.jave.Encoder
import ws.schild.jave.MultimediaObject
import ws.schild.jave.encode.AudioAttributes
import ws.schild.jave.encode.EncodingAttributes
import java.io.File

object AudioConverter {

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma",
        "opus", "ape", "aiff", "aif"
    )

    fun isSupportedExtension(extension: String?): Boolean {
        return extension?.lowercase() in SUPPORTED_EXTENSIONS
    }

    fun convertToWav(sourceFile: File): File? {
        if (!sourceFile.exists()) return null

        val extension = sourceFile.extension.lowercase()
        if (extension == "wav") {
            return try {
                javax.sound.sampled.AudioSystem.getAudioInputStream(sourceFile)
                sourceFile
            } catch (e: Exception) {
                convertWithJave(sourceFile)
            }
        }

        return convertWithJave(sourceFile)
    }

    private fun convertWithJave(sourceFile: File): File? {
        return try {
            val targetFile = File.createTempFile("audioplayer_", ".wav")
            targetFile.deleteOnExit()

            val audio = AudioAttributes().apply {
                setCodec("pcm_s16le")
                setChannels(2)
                setSamplingRate(44100)
            }

            val attrs = EncodingAttributes().apply {
                setOutputFormat("wav")
                setAudioAttributes(audio)
            }

            val encoder = Encoder()
            encoder.encode(MultimediaObject(sourceFile), targetFile, attrs)
            targetFile
        } catch (e: Exception) {
            null
        }
    }
}
