# Audio Player Plugin Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JetBrains IDEで音声ファイルを開くと再生/一時停止/停止・シーク・音量・ループ制御付きのカスタムエディタを表示するプラグインを作る。

**Architecture:** JAVE2（ffmpeg同梱）で任意の音声フォーマットをwavに変換し、Java Sound API（Clip）で再生する。FileEditorProviderで音声拡張子を検出し、Swing UIのカスタムエディタを表示する。

**Tech Stack:** Kotlin, Gradle (Kotlin DSL), IntelliJ Platform Gradle Plugin 2.12.0, JAVE2 3.5.0, Java Sound API

---

## File Structure

```
audio-plugin/
├── build.gradle.kts                          # Gradle build config
├── settings.gradle.kts                       # Project settings
├── gradle.properties                         # Plugin/platform versions
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── com/github/audioplayer/
│       │       ├── AudioFileEditorProvider.kt  # FileEditorProvider - detects audio files
│       │       ├── AudioFileEditor.kt          # FileEditor - wraps the UI panel
│       │       ├── AudioPlayerPanel.kt         # Swing UI - buttons, sliders, labels
│       │       ├── AudioPlayerService.kt       # Playback logic - convert, play, seek, volume
│       │       └── AudioConverter.kt           # JAVE2 conversion wrapper
│       └── resources/
│           └── META-INF/
│               └── plugin.xml                  # Plugin descriptor
└── src/
    └── test/
        └── kotlin/
            └── com/github/audioplayer/
                ├── AudioConverterTest.kt       # Conversion tests
                └── AudioPlayerServiceTest.kt   # Playback logic tests
```

---

## Chunk 1: Project Setup & Build System

### Task 1: Gradle プロジェクトセットアップ

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Delete: `audio-plugin.iml`
- Delete: `src/Main.kt`

- [ ] **Step 1: Gradle Wrapper を生成**

```bash
cd /Users/aida/projects/audio-plugin
gradle wrapper --gradle-version 8.13
```

- [ ] **Step 2: settings.gradle.kts を作成**

```kotlin
// settings.gradle.kts
rootProject.name = "audio-player-plugin"

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.12.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
```

- [ ] **Step 3: gradle.properties を作成**

```properties
# gradle.properties
pluginVersion=1.0.0
platformVersion=2024.3
javaVersion=17
```

- [ ] **Step 4: build.gradle.kts を作成**

```kotlin
// build.gradle.kts
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

val platformVersion: String by project
val pluginVersion: String by project
val javaVersion: String by project

group = "com.github.audioplayer"
version = pluginVersion

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(platformVersion)
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }

    implementation("ws.schild:jave-all-deps:3.5.0")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.audioplayer"
        name = "Audio Player"
        version = pluginVersion
        description = "Play audio files directly in the editor with playback controls."
    }
}

kotlin {
    jvmToolchain(javaVersion.toInt())
}
```

- [ ] **Step 5: 旧ファイルを削除**

```bash
rm -f audio-plugin.iml src/Main.kt
```

- [ ] **Step 6: ソースディレクトリ構造を作成**

```bash
mkdir -p src/main/kotlin/com/github/audioplayer
mkdir -p src/main/resources/META-INF
mkdir -p src/test/kotlin/com/github/audioplayer
```

- [ ] **Step 7: plugin.xml を作成**

```xml
<!-- src/main/resources/META-INF/plugin.xml -->
<idea-plugin>
    <id>com.github.audioplayer</id>
    <name>Audio Player</name>
    <vendor>audio-player</vendor>
    <description>Play audio files (mp3, wav, ogg, flac, aac, m4a, wma) directly in the editor.</description>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <fileEditorProvider
            implementation="com.github.audioplayer.AudioFileEditorProvider"
            id="audioFileEditor"
            order="first"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 8: ビルド確認**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL（ソースがまだないのでコンパイルエラーにならないことを確認）

- [ ] **Step 9: コミット**

```bash
git add build.gradle.kts settings.gradle.kts gradle.properties gradle/ gradlew gradlew.bat src/main/resources/META-INF/plugin.xml
git rm audio-plugin.iml src/Main.kt
git commit -m "feat: setup Gradle project for IntelliJ audio player plugin"
```

---

## Chunk 2: Audio Converter (JAVE2)

### Task 2: AudioConverter — 音声ファイルをwavに変換

**Files:**
- Create: `src/main/kotlin/com/github/audioplayer/AudioConverter.kt`
- Create: `src/test/kotlin/com/github/audioplayer/AudioConverterTest.kt`

- [ ] **Step 1: テストを書く**

```kotlin
// src/test/kotlin/com/github/audioplayer/AudioConverterTest.kt
package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioSystem

class AudioConverterTest {

    @Test
    fun `convertToWav returns original file if already wav`() {
        // Create a minimal wav file for testing
        val wavFile = File.createTempFile("test", ".wav")
        wavFile.deleteOnExit()
        createMinimalWavFile(wavFile)

        val result = AudioConverter.convertToWav(wavFile)
        // For wav files, should return a playable file (may be same or converted)
        assertNotNull(result)
        assertTrue(result.exists())
    }

    @Test
    fun `convertToWav returns null for non-existent file`() {
        val fakeFile = File("/tmp/nonexistent_audio_file.mp3")
        val result = AudioConverter.convertToWav(fakeFile)
        assertNull(result)
    }

    private fun createMinimalWavFile(file: File) {
        // Write a minimal valid WAV header (44 bytes) with no audio data
        val header = ByteArray(44)
        // RIFF header
        "RIFF".toByteArray().copyInto(header, 0)
        // File size - 8
        intToLittleEndian(36).copyInto(header, 4)
        "WAVE".toByteArray().copyInto(header, 8)
        // fmt chunk
        "fmt ".toByteArray().copyInto(header, 12)
        intToLittleEndian(16).copyInto(header, 16) // chunk size
        shortToLittleEndian(1).copyInto(header, 20) // PCM
        shortToLittleEndian(1).copyInto(header, 22) // mono
        intToLittleEndian(44100).copyInto(header, 24) // sample rate
        intToLittleEndian(88200).copyInto(header, 28) // byte rate
        shortToLittleEndian(2).copyInto(header, 32) // block align
        shortToLittleEndian(16).copyInto(header, 34) // bits per sample
        // data chunk
        "data".toByteArray().copyInto(header, 36)
        intToLittleEndian(0).copyInto(header, 40) // data size
        file.writeBytes(header)
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```bash
./gradlew test
```

Expected: FAIL — `AudioConverter` クラスが存在しない

- [ ] **Step 3: AudioConverter を実装**

```kotlin
// src/main/kotlin/com/github/audioplayer/AudioConverter.kt
package com.github.audioplayer

import ws.schild.jave.Encoder
import ws.schild.jave.MultimediaObject
import ws.schild.jave.encode.AudioAttributes
import ws.schild.jave.encode.EncodingAttributes
import java.io.File

object AudioConverter {

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma",
        "opus", "ape", "alac", "aiff", "aif"
    )

    fun isSupportedExtension(extension: String?): Boolean {
        return extension?.lowercase() in SUPPORTED_EXTENSIONS
    }

    /**
     * Converts the given audio file to WAV format for playback via Java Sound API.
     * Returns null if the file doesn't exist or conversion fails.
     * For WAV files, attempts to return the original if already PCM-compatible,
     * otherwise converts.
     */
    fun convertToWav(sourceFile: File): File? {
        if (!sourceFile.exists()) return null

        val extension = sourceFile.extension.lowercase()
        if (extension == "wav") {
            // Try to use directly; if Java Sound can't read it, convert
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
```

- [ ] **Step 4: テストが通ることを確認**

```bash
./gradlew test
```

Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add src/main/kotlin/com/github/audioplayer/AudioConverter.kt src/test/kotlin/com/github/audioplayer/AudioConverterTest.kt
git commit -m "feat: add AudioConverter for audio-to-wav conversion via JAVE2"
```

---

## Chunk 3: Audio Player Service

### Task 3: AudioPlayerService — 再生ロジック

**Files:**
- Create: `src/main/kotlin/com/github/audioplayer/AudioPlayerService.kt`
- Create: `src/test/kotlin/com/github/audioplayer/AudioPlayerServiceTest.kt`

- [ ] **Step 1: テストを書く**

```kotlin
// src/test/kotlin/com/github/audioplayer/AudioPlayerServiceTest.kt
package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioPlayerServiceTest {

    @Test
    fun `formatTime formats seconds correctly`() {
        assertEquals("00:00", AudioPlayerService.formatTime(0))
        assertEquals("01:05", AudioPlayerService.formatTime(65))
        assertEquals("10:00", AudioPlayerService.formatTime(600))
        assertEquals("1:00:00", AudioPlayerService.formatTime(3600))
    }

    @Test
    fun `initial state is STOPPED`() {
        val service = AudioPlayerService()
        assertEquals(AudioPlayerService.PlaybackState.STOPPED, service.state)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```bash
./gradlew test
```

Expected: FAIL — `AudioPlayerService` クラスが存在しない

- [ ] **Step 3: AudioPlayerService を実装**

```kotlin
// src/main/kotlin/com/github/audioplayer/AudioPlayerService.kt
package com.github.audioplayer

import java.io.File
import javax.sound.sampled.*

class AudioPlayerService {

    enum class PlaybackState { PLAYING, PAUSED, STOPPED }

    var state: PlaybackState = PlaybackState.STOPPED
        private set

    private var clip: Clip? = null
    private var pausePosition: Long = 0
    private var wavFile: File? = null
    private var isLooping = false

    var onStateChanged: ((PlaybackState) -> Unit)? = null
    var onPositionChanged: ((Long, Long) -> Unit)? = null // (currentMicros, totalMicros)
    var onError: ((String) -> Unit)? = null

    val totalMicroseconds: Long
        get() = clip?.microsecondLength ?: 0

    val currentMicroseconds: Long
        get() = clip?.microsecondPosition ?: 0

    fun load(sourceFile: File) {
        stop()
        val converted = AudioConverter.convertToWav(sourceFile)
        if (converted == null) {
            onError?.invoke("Failed to load audio file: ${sourceFile.name}")
            return
        }
        wavFile = converted

        try {
            val audioStream = AudioSystem.getAudioInputStream(converted)
            clip = AudioSystem.getClip().apply {
                open(audioStream)
                addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP && state == PlaybackState.PLAYING) {
                        // Natural end of playback
                        if (!isLooping && microsecondPosition >= microsecondLength) {
                            state = PlaybackState.STOPPED
                            onStateChanged?.invoke(state)
                        }
                    }
                }
            }
        } catch (e: Exception) {
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
            // Convert 0-100% to dB scale
            val dB = if (percent <= 0f) {
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
        // Clean up temp wav file if it's not the original
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
```

- [ ] **Step 4: テストが通ることを確認**

```bash
./gradlew test
```

Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add src/main/kotlin/com/github/audioplayer/AudioPlayerService.kt src/test/kotlin/com/github/audioplayer/AudioPlayerServiceTest.kt
git commit -m "feat: add AudioPlayerService with play/pause/stop/seek/volume/loop"
```

---

## Chunk 4: UI Panel

### Task 4: AudioPlayerPanel — Swing UI

**Files:**
- Create: `src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt`

- [ ] **Step 1: AudioPlayerPanel を実装**

```kotlin
// src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
package com.github.audioplayer

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.event.ChangeEvent

class AudioPlayerPanel(private val file: VirtualFile) : JPanel(BorderLayout()) {

    private val playerService = AudioPlayerService()

    private val playPauseButton = JButton("▶")
    private val stopButton = JButton("⏹")
    private val loopButton = JToggleButton("🔁")
    private val seekSlider = JSlider(0, 1000, 0)
    private val volumeSlider = JSlider(0, 100, 80)
    private val timeLabel = JLabel("00:00 / 00:00")
    private val fileNameLabel = JLabel(file.name)
    private val statusLabel = JLabel("")

    private var isSeeking = false
    private var positionTimer: Timer? = null

    init {
        border = JBUI.Borders.empty(20)
        background = JBColor.background()
        setupUI()
        setupListeners()
        loadFile()
    }

    private fun setupUI() {
        // File name at top
        fileNameLabel.font = fileNameLabel.font.deriveFont(Font.BOLD, 16f)
        fileNameLabel.horizontalAlignment = SwingConstants.CENTER

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(fileNameLabel, BorderLayout.CENTER)
            add(timeLabel, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(16)
        }

        // Seek slider
        seekSlider.apply {
            isOpaque = false
            toolTipText = "Seek"
        }

        val seekPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(seekSlider, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(12)
        }

        // Playback controls
        playPauseButton.apply {
            font = font.deriveFont(18f)
            toolTipText = "Play / Pause"
            preferredSize = Dimension(60, 40)
        }
        stopButton.apply {
            font = font.deriveFont(18f)
            toolTipText = "Stop"
            preferredSize = Dimension(60, 40)
        }
        loopButton.apply {
            font = font.deriveFont(14f)
            toolTipText = "Loop"
            preferredSize = Dimension(60, 40)
        }

        val controlsPanel = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
            isOpaque = false
            add(playPauseButton)
            add(stopButton)
            add(loopButton)
        }

        // Volume control
        val volumeLabel = JLabel("🔊")
        volumeSlider.apply {
            isOpaque = false
            preferredSize = Dimension(120, 20)
            toolTipText = "Volume"
        }

        val volumePanel = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
            isOpaque = false
            add(volumeLabel)
            add(volumeSlider)
            border = JBUI.Borders.emptyTop(8)
        }

        // Status label
        statusLabel.apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.RED
        }

        // Center everything vertically
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(topPanel)
            add(seekPanel)
            add(controlsPanel)
            add(volumePanel)
            add(Box.createVerticalStrut(8))
            add(statusLabel)
        }

        val wrapperPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(centerPanel)
        }

        add(wrapperPanel, BorderLayout.CENTER)
    }

    private fun setupListeners() {
        playPauseButton.addActionListener {
            when (playerService.state) {
                AudioPlayerService.PlaybackState.PLAYING -> playerService.pause()
                else -> playerService.play()
            }
        }

        stopButton.addActionListener {
            playerService.stop()
            seekSlider.value = 0
        }

        loopButton.addActionListener {
            playerService.setLooping(loopButton.isSelected)
        }

        seekSlider.addChangeListener { _: ChangeEvent ->
            if (seekSlider.valueIsAdjusting) {
                isSeeking = true
                val total = playerService.totalMicroseconds
                val target = (seekSlider.value.toLong() * total) / 1000
                updateTimeLabel(target, total)
            } else if (isSeeking) {
                isSeeking = false
                val total = playerService.totalMicroseconds
                val target = (seekSlider.value.toLong() * total) / 1000
                playerService.seek(target)
            }
        }

        volumeSlider.addChangeListener {
            playerService.setVolume(volumeSlider.value.toFloat())
        }

        playerService.onStateChanged = { state ->
            SwingUtilities.invokeLater {
                when (state) {
                    AudioPlayerService.PlaybackState.PLAYING -> {
                        playPauseButton.text = "⏸"
                        startPositionTimer()
                    }
                    AudioPlayerService.PlaybackState.PAUSED -> {
                        playPauseButton.text = "▶"
                        stopPositionTimer()
                    }
                    AudioPlayerService.PlaybackState.STOPPED -> {
                        playPauseButton.text = "▶"
                        seekSlider.value = 0
                        stopPositionTimer()
                        updateTimeLabel(0, playerService.totalMicroseconds)
                    }
                }
            }
        }

        playerService.onError = { message ->
            SwingUtilities.invokeLater {
                statusLabel.text = message
            }
        }
    }

    private fun loadFile() {
        statusLabel.text = "Loading..."
        Thread {
            playerService.load(File(file.path))
            SwingUtilities.invokeLater {
                statusLabel.text = ""
                playerService.setVolume(volumeSlider.value.toFloat())
                updateTimeLabel(0, playerService.totalMicroseconds)
            }
        }.start()
    }

    private fun startPositionTimer() {
        stopPositionTimer()
        positionTimer = Timer(100) {
            if (!isSeeking) {
                val current = playerService.currentMicroseconds
                val total = playerService.totalMicroseconds
                if (total > 0) {
                    seekSlider.value = ((current * 1000) / total).toInt()
                }
                updateTimeLabel(current, total)
            }
        }.apply { start() }
    }

    private fun stopPositionTimer() {
        positionTimer?.stop()
        positionTimer = null
    }

    private fun updateTimeLabel(currentMicros: Long, totalMicros: Long) {
        val currentSec = currentMicros / 1_000_000
        val totalSec = totalMicros / 1_000_000
        timeLabel.text = "${AudioPlayerService.formatTime(currentSec)} / ${AudioPlayerService.formatTime(totalSec)}"
    }

    fun dispose() {
        stopPositionTimer()
        playerService.dispose()
    }
}
```

- [ ] **Step 2: ビルド確認**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミット**

```bash
git add src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: add AudioPlayerPanel with playback controls UI"
```

---

## Chunk 5: FileEditor & FileEditorProvider

### Task 5: AudioFileEditor & AudioFileEditorProvider

**Files:**
- Create: `src/main/kotlin/com/github/audioplayer/AudioFileEditorProvider.kt`
- Create: `src/main/kotlin/com/github/audioplayer/AudioFileEditor.kt`

- [ ] **Step 1: AudioFileEditor を実装**

```kotlin
// src/main/kotlin/com/github/audioplayer/AudioFileEditor.kt
package com.github.audioplayer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class AudioFileEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val panel = AudioPlayerPanel(file)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "Audio Player"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        panel.dispose()
    }
}
```

- [ ] **Step 2: AudioFileEditorProvider を実装**

```kotlin
// src/main/kotlin/com/github/audioplayer/AudioFileEditorProvider.kt
package com.github.audioplayer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AudioFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return AudioConverter.isSupportedExtension(file.extension)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return AudioFileEditor(file)
    }

    override fun getEditorTypeId(): String = "audio-player-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
```

- [ ] **Step 3: ビルド確認**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/kotlin/com/github/audioplayer/AudioFileEditor.kt src/main/kotlin/com/github/audioplayer/AudioFileEditorProvider.kt
git commit -m "feat: add FileEditor and FileEditorProvider for audio files"
```

---

## Chunk 6: Integration Test & Final Verification

### Task 6: 最終ビルドとプラグイン動作確認

- [ ] **Step 1: フルビルド**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: テスト実行**

```bash
./gradlew test
```

Expected: All tests PASS

- [ ] **Step 3: プラグインのサンドボックス IDE で動作確認**

```bash
./gradlew runIde
```

Expected: IntelliJ IDEAが起動し、音声ファイルを開くとカスタムエディタが表示される

- [ ] **Step 4: 最終コミット（必要であれば修正後）**

```bash
git add -A
git commit -m "feat: complete audio player plugin v1.0.0"
```
