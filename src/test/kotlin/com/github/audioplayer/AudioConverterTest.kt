package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioConverterTest {

    @Test
    fun `isSupportedExtension returns true for mp3`() {
        assertTrue(AudioConverter.isSupportedExtension("mp3"))
    }

    @Test
    fun `isSupportedExtension returns true for wav`() {
        assertTrue(AudioConverter.isSupportedExtension("wav"))
    }

    @Test
    fun `isSupportedExtension returns true for flac`() {
        assertTrue(AudioConverter.isSupportedExtension("flac"))
    }

    @Test
    fun `isSupportedExtension returns false for txt`() {
        assertFalse(AudioConverter.isSupportedExtension("txt"))
    }

    @Test
    fun `isSupportedExtension returns false for null`() {
        assertFalse(AudioConverter.isSupportedExtension(null))
    }

    @Test
    fun `isSupportedExtension is case insensitive`() {
        assertTrue(AudioConverter.isSupportedExtension("MP3"))
        assertTrue(AudioConverter.isSupportedExtension("Wav"))
    }

    @Test
    fun `findFfmpeg returns path when ffmpeg is installed`() {
        val path = AudioConverter.findFfmpeg()
        // ffmpeg should be installed on the dev machine
        assertNotNull("ffmpeg not found on PATH", path)
        assertTrue(File(path!!).exists())
    }

    @Test
    fun `convertToWav returns null for non-existent file`() {
        val fakeFile = File("/tmp/nonexistent_audio_file_12345.mp3")
        val result = AudioConverter.convertToWav(fakeFile)
        assertNull(result)
    }

    @Test
    fun `convertToWav returns original file for valid wav`() {
        val wavFile = File.createTempFile("test", ".wav")
        wavFile.deleteOnExit()
        createMinimalWavFile(wavFile)

        val result = AudioConverter.convertToWav(wavFile)
        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    private fun createMinimalWavFile(file: File) {
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        intToLE(36).copyInto(header, 4)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        intToLE(16).copyInto(header, 16)
        shortToLE(1).copyInto(header, 20)
        shortToLE(1).copyInto(header, 22)
        intToLE(44100).copyInto(header, 24)
        intToLE(88200).copyInto(header, 28)
        shortToLE(2).copyInto(header, 32)
        shortToLE(16).copyInto(header, 34)
        "data".toByteArray().copyInto(header, 36)
        intToLE(0).copyInto(header, 40)
        file.writeBytes(header)
    }

    private fun intToLE(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToLE(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )
}
