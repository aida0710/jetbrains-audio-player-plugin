package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File

data class AudioMetadata(
    val encoding: String,
    val format: String,
    val channels: Int,
    val channelLayout: String,
    val sampleRate: Int,
    val fileSize: Long,
    val durationSeconds: Double,
)

object AudioProbe {
    private val LOG = Logger.getInstance(AudioProbe::class.java)

    fun probe(file: File): AudioMetadata? {
        if (!file.exists()) return null

        val ffprobe = FfmpegPathUtil.findFfprobe()
        if (ffprobe == null) {
            LOG.warn("ffprobe not found")
            return null
        }

        return try {
            val process =
                ProcessBuilder(
                    ffprobe,
                    "-v",
                    "quiet",
                    "-print_format",
                    "json",
                    "-show_format",
                    "-show_streams",
                    "-select_streams",
                    "a:0",
                    file.absolutePath,
                ).redirectErrorStream(true).start()

            val json = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                LOG.error("ffprobe exited with code $exitCode")
                return null
            }

            parseJson(json, file.length())
        } catch (e: Exception) {
            LOG.error("ffprobe failed", e)
            null
        }
    }

    internal fun parseJson(
        json: String,
        fileSize: Long,
    ): AudioMetadata? =
        try {
            // 外部ライブラリなしのシンプルなJSON解析
            val codecName = extractJsonValue(json, "codec_name") ?: "unknown"
            val sampleFmt = extractJsonValue(json, "sample_fmt") ?: "unknown"
            val channels = extractJsonValue(json, "channels")?.toIntOrNull() ?: 0
            val channelLayout =
                extractJsonValue(json, "channel_layout")
                    ?: when (channels) {
                        1 -> {
                            "mono"
                        }
                        2 -> {
                            "stereo"
                        }
                        else -> {
                            "${channels}ch"
                        }
                    }
            val sampleRate = extractJsonValue(json, "sample_rate")?.toIntOrNull() ?: 0
            val duration = extractJsonValue(json, "duration")?.toDoubleOrNull() ?: 0.0

            AudioMetadata(
                encoding = codecName,
                format = sampleFmt,
                channels = channels,
                channelLayout = channelLayout,
                sampleRate = sampleRate,
                fileSize = fileSize,
                durationSeconds = duration,
            )
        } catch (e: Exception) {
            LOG.error("Failed to parse ffprobe JSON", e)
            null
        }

    private fun extractJsonValue(
        json: String,
        key: String,
    ): String? {
        // "key": "value" または "key": 数値 にマッチ
        val pattern = """"$key"\s*:\s*"?([^",}\n]+)"?""".toRegex()
        return pattern
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.trim()
    }

    fun formatFileSize(bytes: Long): String =
        when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes bytes"
        }

    fun formatSampleRate(hz: Int): String = "%,d Hz".format(hz)

    fun formatChannels(
        channels: Int,
        layout: String,
    ): String = "$channels ch ($layout)"
}
