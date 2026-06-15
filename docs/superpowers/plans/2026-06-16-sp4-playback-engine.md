# SP4: 再生エンジン（VUメーター＋音程維持の速度変更）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 現行 Clip エンジン上で VU/ピークメーターと音程維持の速度変更を実装する（SourceDataLine 移行は見送り＝設計仕様参照）。

**Architecture:** レベル算出・位置マッピング・atempoコマンドは純粋関数でTDD。速度変更は ffmpeg atempo で速度調整WAVを生成し Clip を差し替え、UIの時間軸は常に元基準（speed=1.0 はマッピング恒等＝既存挙動と等価）。

**Tech Stack:** Kotlin, javax.sound.sampled, ffmpeg, Swing, JUnit 4, ktlint。

**設計仕様:** `docs/superpowers/specs/2026-06-16-sp4-playback-engine-design.md`

**共通の注意:** コミット前 `./gradlew ktlintFormat`。`runIde` 実行しない（速度/音程/メーターの体感確認は人手のフォローアップ）。`build-signed.sh`/`build.gradle.kts` に触れない。各タスク列挙ファイルのみ `git add`。各タスク後 `./gradlew test`+`buildPlugin` 緑。

---

## Task 1: 純粋関数（レベル・位置マッピング・atempoコマンド）

**Files:** Modify `AudioPlayerService.kt`, `AudioConverter.kt`; Test `AudioPlayerServiceTest.kt`, `AudioConverterTest.kt`.

- [ ] **Step 1: Failing tests (AudioPlayerService)** — add to `AudioPlayerServiceTest.kt` before final `}`:

```kotlin
    @Test
    fun `computePeak returns max abs normalized`() {
        assertEquals(1.0f, AudioPlayerService.computePeak(shortArrayOf(0, 16384, -32768)), 0.001f)
        assertEquals(0.5f, AudioPlayerService.computePeak(shortArrayOf(16384)), 0.01f)
        assertEquals(0.0f, AudioPlayerService.computePeak(shortArrayOf()), 0.0f)
    }

    @Test
    fun `computeRms returns normalized rms`() {
        assertEquals(0.0f, AudioPlayerService.computeRms(shortArrayOf(0, 0)), 0.0f)
        assertEquals(1.0f, AudioPlayerService.computeRms(shortArrayOf(32767, -32767)), 0.01f)
        assertEquals(0.0f, AudioPlayerService.computeRms(shortArrayOf()), 0.0f)
    }

    @Test
    fun `renderedToOriginalMicros scales by speed`() {
        assertEquals(20_000_000L, AudioPlayerService.renderedToOriginalMicros(10_000_000L, 2.0f))
        assertEquals(10_000_000L, AudioPlayerService.renderedToOriginalMicros(10_000_000L, 1.0f))
    }

    @Test
    fun `originalToRenderedMicros divides by speed`() {
        assertEquals(10_000_000L, AudioPlayerService.originalToRenderedMicros(20_000_000L, 2.0f))
        assertEquals(20_000_000L, AudioPlayerService.originalToRenderedMicros(10_000_000L, 0.5f))
    }

    @Test
    fun `bytesToShorts converts little-endian 16-bit`() {
        // 0x0000=0, 0x0100=256 (LE), 0xFFFF=-1
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xFF.toByte(), 0xFF.toByte())
        val shorts = AudioPlayerService.bytesToShorts(bytes, 6)
        assertEquals(0.toShort(), shorts[0])
        assertEquals(256.toShort(), shorts[1])
        assertEquals((-1).toShort(), shorts[2])
    }
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.AudioPlayerServiceTest"` → compile error.

- [ ] **Step 3: Implement pure functions** — in `AudioPlayerService.kt` `companion object` (alongside `formatTime`/`computeSeekTarget`):

```kotlin
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
```

- [ ] **Step 4: Failing test (AudioConverter)** — add to `AudioConverterTest.kt`:

```kotlin
    @Test
    fun `buildAtempoCommand builds pitch-preserving tempo command`() {
        val cmd = AudioConverter.buildAtempoCommand("/bin/ffmpeg", "/tmp/in.wav", "/tmp/out.wav", 1.5f)
        assertEquals("/bin/ffmpeg", cmd[0])
        assertEquals("/tmp/in.wav", cmd[cmd.indexOf("-i") + 1])
        assertEquals("atempo=1.5", cmd[cmd.indexOf("-filter:a") + 1])
        assertEquals("/tmp/out.wav", cmd.last())
    }
```

- [ ] **Step 5: Implement buildAtempoCommand** — add to `AudioConverter.kt`:

```kotlin
    fun buildAtempoCommand(
        ffmpeg: String,
        input: String,
        output: String,
        speed: Float,
    ): List<String> =
        listOf(
            ffmpeg, "-i", input, "-filter:a", "atempo=$speed",
            "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", "-y", output,
        )

    fun renderAtempo(
        input: File,
        output: File,
        speed: Float,
    ): Boolean {
        val ffmpeg = FfmpegPathUtil.findFfmpeg() ?: return false
        return try {
            val process =
                ProcessBuilder(buildAtempoCommand(ffmpeg, input.absolutePath, output.absolutePath, speed))
                    .redirectErrorStream(true)
                    .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (code != 0) LOG.error("atempo failed ($code): $out")
            code == 0
        } catch (e: Exception) {
            LOG.error("atempo render failed", e)
            false
        }
    }
```

- [ ] **Step 6: Verify pass** — `./gradlew test --tests "com.github.audioplayer.AudioPlayerServiceTest"` and `--tests "com.github.audioplayer.AudioConverterTest"` → PASS.

- [ ] **Step 7: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerService.kt \
        src/main/kotlin/com/github/audioplayer/AudioConverter.kt \
        src/test/kotlin/com/github/audioplayer/AudioPlayerServiceTest.kt \
        src/test/kotlin/com/github/audioplayer/AudioConverterTest.kt
git commit -m "feat: レベル算出/位置マッピング/atempoコマンドの純粋関数を追加"
```

---

## Task 2: AudioPlayerService エンジン拡張（速度変更・レベル取得）＋設定

**Files:** Modify `AudioPlayerService.kt`, `AudioPlayerSettings.kt`; Test `AudioPlayerSettingsTest.kt`.

Read the current `AudioPlayerService.kt` first. Keep the public API (state, play/pause/stop/seek/setVolume/setLooping, totalMicroseconds, currentMicroseconds, onStateChanged, onError, load, dispose). speed=1.0 must remain behaviorally identical (existing AudioPlayerServiceTest must stay green).

- [ ] **Step 1: Add settings field** — in `AudioPlayerSettings.kt` `SettingsState`, after `waveformSplitChannels: Boolean = false,` add `var lastSpeed: Float = 1.0f,`. Add test to `AudioPlayerSettingsTest.kt`:

```kotlin
    @Test
    fun `default lastSpeed is 1`() {
        assertEquals(1.0f, AudioPlayerSettings.SettingsState().lastSpeed, 0.0f)
    }
```

- [ ] **Step 2: Engine fields** — in `AudioPlayerService.kt`, add fields (near `clip`/`pausePosition`/`wavFile`/`isLooping`):

```kotlin
    private var speed: Float = 1.0f
    private var renderedWav: File? = null
    private var activeWav: File? = null
    private var volumePercent: Float = 80f

    var originalTotalMicros: Long = 0
        private set
```

- [ ] **Step 3: Refactor clip opening + load** — extract clip opening into `openClip`, and update `load` to use it and set `originalTotalMicros`/`activeWav`. Replace the body of `load` (after obtaining `converted`) and add `openClip`:

```kotlin
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
```

- [ ] **Step 4: Mapping in getters/seek/volume** — change:

```kotlin
    val totalMicroseconds: Long
        get() = originalTotalMicros

    val currentMicroseconds: Long
        get() = renderedToOriginalMicros(clip?.microsecondPosition ?: 0, speed)
```

In `seek(microseconds)` (microseconds is ORIGINAL-timeline), map to rendered:

```kotlin
    fun seek(microseconds: Long) {
        val c = clip ?: return
        val originalClamped = microseconds.coerceIn(0, originalTotalMicros)
        val rendered = originalToRenderedMicros(originalClamped, speed).coerceIn(0, c.microsecondLength)
        c.microsecondPosition = rendered
        pausePosition = rendered
    }
```

In `setVolume(percent)`, store it: add `volumePercent = percent` at the top (after `val c = clip ?: return`? — store BEFORE the early return so it's remembered even if clip null). Restructure:

```kotlin
    fun setVolume(percent: Float) {
        volumePercent = percent
        val c = clip ?: return
        try {
            val gainControl = c.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val dB = if (percent <= 0f) gainControl.minimum else 20f * Math.log10(percent / 100.0).toFloat()
            gainControl.value = dB.coerceIn(gainControl.minimum, gainControl.maximum)
        } catch (_: Exception) {
        }
    }
```

- [ ] **Step 5: setSpeed + currentLevel + dispose** — add:

```kotlin
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
```

Update `dispose()` to also delete `renderedWav`:

```kotlin
    fun dispose() {
        clip?.stop()
        clip?.close()
        clip = null
        wavFile?.let { if (it.name.startsWith("audioplayer_")) it.delete() }
        renderedWav?.let { if (it.name.startsWith("audioplayer_")) it.delete() }
        wavFile = null
        renderedWav = null
        activeWav = null
        state = PlaybackState.STOPPED
    }
```

(Keep `play`/`pause`/`stop`/`setLooping` unchanged — they operate on `clip` in the rendered domain, which is correct.)

- [ ] **Step 6: Verify** — `./gradlew test` (full; existing AudioPlayerServiceTest must pass — speed defaults 1.0, mapping identity), `./gradlew buildPlugin`.

- [ ] **Step 7: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerService.kt \
        src/main/kotlin/com/github/audioplayer/AudioPlayerSettings.kt \
        src/test/kotlin/com/github/audioplayer/AudioPlayerSettingsTest.kt
git commit -m "feat: 音程維持の速度変更(atempo)とレベル取得をAudioPlayerServiceに実装"
```

---

## Task 3: UI（速度コンボ＋レベルメーター）

**Files:** New `LevelMeterBar.kt`; Modify `AudioPlayerPanel.kt`.

`runIde` 実行不可（体感確認は人手）。`buildPlugin`+`test` で確認。

- [ ] **Step 1: LevelMeterBar component** — create `src/main/kotlin/com/github/audioplayer/LevelMeterBar.kt`:

```kotlin
package com.github.audioplayer

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JComponent

/** ピーク(枠線)とRMS(塗り)を 0..1 で表示する軽量レベルメーター。 */
class LevelMeterBar : JComponent() {
    var rms: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            repaint()
        }

    var peak: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            repaint()
        }

    override fun getPreferredSize(): Dimension = Dimension(120, 12)

    override fun getMinimumSize(): Dimension = Dimension(40, 8)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val w = width
        val h = height
        g.color = JBColor(Color(0x33, 0x33, 0x33), Color(0x22, 0x22, 0x22))
        g.fillRect(0, 0, w, h)
        val rmsW = (rms * w).toInt()
        g.color = if (rms > 0.9f) JBColor.RED else JBColor(Color(0x4C, 0xAF, 0x50), Color(0x4C, 0xAF, 0x50))
        g.fillRect(0, 0, rmsW, h)
        val peakX = (peak * w).toInt().coerceIn(0, w - 1)
        g.color = JBColor(Color(0xFF, 0xC1, 0x07), Color(0xFF, 0xC1, 0x07))
        g.fillRect(peakX, 0, 2, h)
    }
}
```

- [ ] **Step 2: Fields** — in `AudioPlayerPanel.kt` add:

```kotlin
    private val speedCombo = JComboBox(arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"))
    private val levelMeter = LevelMeterBar()
```

(`JComboBox` is in `javax.swing.*`.)

- [ ] **Step 3: Place in controls** — in `createControlsPanel()`, add a row near the volume panel containing a `JLabel("Speed")`, `speedCombo`, and the `levelMeter`. Add to the `centerPanel` `BoxLayout` after the volume panel:

```kotlin
        val speedPanel =
            JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
                isOpaque = false
                add(JLabel("Speed"))
                add(speedCombo)
                add(levelMeter)
            }
```

and `add(speedPanel)` into centerPanel (after the volume panel `add`).

- [ ] **Step 4: Init + listener** — in `init` (after persisted volume/loop restore), set the combo from settings by matching the parsed value (default "1.0x"):

```kotlin
        speedCombo.selectedItem =
            (0 until speedCombo.itemCount)
                .map { speedCombo.getItemAt(it) }
                .firstOrNull { it.removeSuffix("x").toFloat() == settingsState.lastSpeed } ?: "1.0x"
```

In `setupListeners()`:

```kotlin
        speedCombo.addActionListener {
            val sel = (speedCombo.selectedItem as? String) ?: return@addActionListener
            val newSpeed = sel.removeSuffix("x").toFloat()
            settingsState.lastSpeed = newSpeed
            speedCombo.isEnabled = false
            Thread {
                playerService.setSpeed(newSpeed)
                SwingUtilities.invokeLater { speedCombo.isEnabled = true }
            }.start()
        }
```

- [ ] **Step 5: Apply persisted speed after load + meter updates** — in `loadFile()`'s `invokeLater`, after volume/loop are applied (when `totalMicroseconds > 0`), apply persisted speed if not 1.0 on a background thread:

```kotlin
                if (settingsState.lastSpeed != 1.0f) {
                    val s = settingsState.lastSpeed
                    Thread { playerService.setSpeed(s) }.start()
                }
```

In `startPositionTimer()`'s `Timer(100) { ... }`, add level update inside the block:

```kotlin
                    val level = playerService.currentLevel()
                    if (level != null) {
                        levelMeter.peak = level.first
                        levelMeter.rms = level.second
                    } else {
                        levelMeter.peak = 0f
                        levelMeter.rms = 0f
                    }
```

Also, when playback stops/pauses (in `onStateChanged` STOPPED/PAUSED branches), set `levelMeter.peak = 0f; levelMeter.rms = 0f`.

- [ ] **Step 6: Verify** — `./gradlew buildPlugin` (success), `./gradlew test` (full green).

- [ ] **Step 7: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/LevelMeterBar.kt \
        src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: 速度選択UIとVU/ピークレベルメーターを追加"
```

---

## 完了条件
- `./gradlew test` 緑（speed=1.0 で既存挙動と等価を含む）、`./gradlew buildPlugin` 成功、`./gradlew ktlintCheck` 違反なし。
- 純粋関数（computePeak/computeRms/位置マッピング/bytesToShorts/atempoコマンド）テスト済み。
- **実機検証（人手）必須**: speed!=1.0 の音程・位置整合、メーター追従。PR/READMEに明記。
