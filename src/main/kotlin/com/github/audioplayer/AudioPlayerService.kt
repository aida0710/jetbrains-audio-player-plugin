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

    var onStateChanged: ((PlaybackState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val totalMicroseconds: Long
        get() = clip?.microsecondLength ?: 0

    val currentMicroseconds: Long
        get() = clip?.microsecondPosition ?: 0

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
        log.info("Converted file: ${converted.absolutePath}, size=${converted.length()}")

        try {
            val audioStream = AudioSystem.getAudioInputStream(converted)
            log.info("AudioInputStream format: ${audioStream.format}")
            clip =
                AudioSystem.getClip().apply {
                    open(audioStream)
                    log.info("Clip opened. Duration: ${microsecondLength / 1_000_000}s")
                    addLineListener { event ->
                        if (event.type == LineEvent.Type.STOP && state == PlaybackState.PLAYING) {
                            if (!isLooping && microsecondPosition >= microsecondLength) {
                                state = PlaybackState.STOPPED
                                onStateChanged?.invoke(state)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            log.error("Failed to open audio clip", e)
            onError?.invoke("Failed to open audio: ${e.message}")
        }
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
        val clamped = microseconds.coerceIn(0, c.microsecondLength)
        c.microsecondPosition = clamped
        pausePosition = clamped
    }

    fun setVolume(percent: Float) {
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
            // Volume control not available
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

    fun dispose() {
        clip?.stop()
        clip?.close()
        clip = null
        wavFile?.let {
            if (it.name.startsWith("audioplayer_")) {
                it.delete()
            }
        }
        wavFile = null
        state = PlaybackState.STOPPED
    }

    companion object {
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
