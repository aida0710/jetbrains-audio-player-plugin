package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import javax.sound.sampled.*

class AudioPlayerService {
    private val log = Logger.getInstance(AudioPlayerService::class.java)

    enum class PlaybackState { PLAYING, PAUSED, STOPPED }

    var state: PlaybackState = PlaybackState.STOPPED
        private set

    private var clip: Clip? = null
    private var pausePosition: Long = 0
    private var wavFile: File? = null
    private var isLooping = false

    private var speed: Float = 1.0f
    private var renderedWav: File? = null
    private var activeWav: File? = null
    private var volumePercent: Float = 80f

    var originalTotalMicros: Long = 0
        private set

    var onStateChanged: ((PlaybackState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val totalMicroseconds: Long
        get() = originalTotalMicros

    val currentMicroseconds: Long
        get() = renderedToOriginalMicros(clip?.microsecondPosition ?: 0, speed)

    fun load(sourceFile: File) {
        stop()
        log.info("Loading audio: ${sourceFile.absolutePath}")
        val converted = AudioConverter.convertToWav(sourceFile)
        if (converted == null) {
            log.error("Conversion returned null for: ${sourceFile.name}")
            onError?.invoke("Failed to load audio file: ${sourceFile.name}")
            return
        }
        wavFile = converted
        speed = 1.0f
        renderedWav = null
        if (openClip(converted)) {
            originalTotalMicros = clip?.microsecondLength ?: 0
        }
    }

    private fun openClip(wav: File): Boolean =
        try {
            clip?.close()
            activeWav = wav
            val audioStream = AudioSystem.getAudioInputStream(wav)
            clip =
                AudioSystem.getClip().apply {
                    open(audioStream)
                    addLineListener { event ->
                        if (event.type == LineEvent.Type.STOP && state == PlaybackState.PLAYING) {
                            if (!isLooping && microsecondPosition >= microsecondLength) {
                                state = PlaybackState.STOPPED
                                onStateChanged?.invoke(state)
                            }
                        }
                    }
                }
            true
        } catch (e: Exception) {
            log.error("Failed to open audio clip", e)
            onError?.invoke("Failed to open audio: ${e.message}")
            false
        }

    fun play() {
        val c = clip ?: return
        if (state == PlaybackState.PAUSED) {
            c.microsecondPosition = pausePosition
        }
        if (isLooping) {
            c.loop(Clip.LOOP_CONTINUOUSLY)
        } else {
            c.start()
        }
        state = PlaybackState.PLAYING
        onStateChanged?.invoke(state)
    }

    fun pause() {
        val c = clip ?: return
        if (state == PlaybackState.PLAYING) {
            pausePosition = c.microsecondPosition
            c.stop()
            state = PlaybackState.PAUSED
            onStateChanged?.invoke(state)
        }
    }

    fun stop() {
        val c = clip ?: return
        c.stop()
        c.microsecondPosition = 0
        pausePosition = 0
        state = PlaybackState.STOPPED
        onStateChanged?.invoke(state)
    }

    fun seek(microseconds: Long) {
        val c = clip ?: return
        val originalClamped = microseconds.coerceIn(0, originalTotalMicros)
        val rendered = originalToRenderedMicros(originalClamped, speed).coerceIn(0, c.microsecondLength)
        c.microsecondPosition = rendered
        pausePosition = rendered
    }

    fun setVolume(percent: Float) {
        volumePercent = percent
        val c = clip ?: return
        try {
            val gainControl = c.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val dB =
                if (percent <= 0f) {
                    gainControl.minimum
                } else {
                    20f * Math.log10(percent / 100.0).toFloat()
                }
            gainControl.value = dB.coerceIn(gainControl.minimum, gainControl.maximum)
        } catch (_: Exception) {
            // 音量コントロール非対応
        }
    }

    fun setLooping(loop: Boolean) {
        isLooping = loop
        val c = clip ?: return
        if (state == PlaybackState.PLAYING) {
            if (loop) {
                c.loop(Clip.LOOP_CONTINUOUSLY)
            } else {
                c.stop()
                c.start()
            }
        }
    }

    fun setSpeed(newSpeed: Float) {
        val base = wavFile ?: return
        if (newSpeed == speed) return
        val wasPlaying = state == PlaybackState.PLAYING
        val originalPos = currentMicroseconds

        val targetWav: File? =
            if (newSpeed == 1.0f) {
                base
            } else {
                val out = File.createTempFile("audioplayer_speed_", ".wav")
                if (AudioConverter.renderAtempo(base, out, newSpeed)) {
                    out
                } else {
                    out.delete()
                    null
                }
            }
        if (targetWav == null) {
            onError?.invoke("速度変更に失敗しました (ffmpeg required)")
            return
        }

        if (state == PlaybackState.PLAYING) {
            clip?.stop()
        }
        renderedWav?.let { if (it != base) it.delete() }
        renderedWav = if (newSpeed == 1.0f) null else targetWav
        speed = newSpeed

        if (!openClip(targetWav)) return
        setVolume(volumePercent)
        val rendered = originalToRenderedMicros(originalPos, newSpeed).coerceIn(0, clip?.microsecondLength ?: 0)
        clip?.microsecondPosition = rendered
        pausePosition = rendered
        if (wasPlaying) {
            play()
        } else {
            state = PlaybackState.PAUSED
            onStateChanged?.invoke(state)
        }
    }

    fun currentLevel(): Pair<Float, Float>? {
        if (state != PlaybackState.PLAYING) return null
        val wav = activeWav ?: return null
        val c = clip ?: return null
        return try {
            val ais = AudioSystem.getAudioInputStream(wav)
            ais.use {
                val fmt = it.format
                val frameSize = fmt.frameSize.coerceAtLeast(1)
                var toSkip = (c.microsecondPosition / 1_000_000.0 * fmt.frameRate).toLong() * frameSize
                while (toSkip > 0) {
                    val skipped = it.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                val buf = ByteArray(2048 * frameSize)
                val n = it.read(buf)
                if (n <= 0) return null
                val samples = bytesToShorts(buf, n)
                computePeak(samples) to computeRms(samples)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun dispose() {
        clip?.stop()
        clip?.close()
        clip = null
        wavFile?.let {
            if (it.name.startsWith("audioplayer_")) {
                it.delete()
            }
        }
        renderedWav?.let {
            if (it.name.startsWith("audioplayer_")) {
                it.delete()
            }
        }
        wavFile = null
        renderedWav = null
        activeWav = null
        state = PlaybackState.STOPPED
    }

    companion object {
        fun computePeak(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            var maxAbs = 0
            for (s in samples) {
                val a = if (s.toInt() == Short.MIN_VALUE.toInt()) 32768 else kotlin.math.abs(s.toInt())
                if (a > maxAbs) maxAbs = a
            }
            return (maxAbs / 32768f).coerceIn(0f, 1f)
        }

        fun computeRms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) {
                val v = s.toDouble()
                sum += v * v
            }
            return (kotlin.math.sqrt(sum / samples.size) / 32768.0).toFloat().coerceIn(0f, 1f)
        }

        fun renderedToOriginalMicros(
            renderedMicros: Long,
            speed: Float,
        ): Long = (renderedMicros * speed).toLong()

        fun originalToRenderedMicros(
            originalMicros: Long,
            speed: Float,
        ): Long = (originalMicros / speed).toLong()

        fun bytesToShorts(
            bytes: ByteArray,
            length: Int,
        ): ShortArray {
            val count = length / 2
            val out = ShortArray(count)
            for (i in 0 until count) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort()
            }
            return out
        }

        fun computeSeekTarget(
            currentMicros: Long,
            deltaMicros: Long,
            totalMicros: Long,
        ): Long = (currentMicros + deltaMicros).coerceIn(0, totalMicros)

        fun formatTime(totalSeconds: Long): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}
