# SP3: 波形表示の強化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 表示窓モデルで波形/スペクトラムを拡大/スクロール可能にし、時間ルーラー＋再生済み塗り＋L/R分離を追加する。

**Architecture:** `TimelineImagePanel` の座標を窓 `[viewStart,viewEnd]` 基準に。窓変更時は ffmpeg `-ss/-t` で区間を再生成。ズーム/パン/ルーラー目盛りは純粋関数でTDD。

**Tech Stack:** Kotlin, Swing, ffmpeg, JUnit 4, ktlint。

**設計仕様:** `docs/superpowers/specs/2026-06-16-sp3-waveform-enhancements-design.md`

**共通の注意:** コミット前 `./gradlew ktlintFormat`。`runIde` 実行しない。`build-signed.sh`/`build.gradle.kts` に触れない。各タスク列挙ファイルのみ `git add`。各タスク後 `./gradlew test`+`buildPlugin` 緑。

---

## Task 1: TimelineImagePanel を窓モデルに（純粋関数＋描画）

**Files:** Modify `TimelineImagePanel.kt`; Modify `TimelineImagePanelTest.kt`.

- [ ] **Step 1: Replace the test file with window-based tests** — overwrite `src/test/kotlin/com/github/audioplayer/TimelineImagePanelTest.kt` with:

```kotlin
package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class TimelineImagePanelTest {
    @Test
    fun `timeAtX maps window edges and midpoint`() {
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(0, 1000, 10_000_000, 20_000_000))
        assertEquals(20_000_000L, TimelineImagePanel.timeAtX(1000, 1000, 10_000_000, 20_000_000))
        assertEquals(15_000_000L, TimelineImagePanel.timeAtX(500, 1000, 10_000_000, 20_000_000))
    }

    @Test
    fun `timeAtX clamps x and guards bad window`() {
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(-10, 1000, 10_000_000, 20_000_000))
        assertEquals(20_000_000L, TimelineImagePanel.timeAtX(5000, 1000, 10_000_000, 20_000_000))
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(500, 0, 10_000_000, 20_000_000))
        assertEquals(10_000_000L, TimelineImagePanel.timeAtX(500, 1000, 10_000_000, 10_000_000))
    }

    @Test
    fun `xAtTime maps within window without clamping`() {
        assertEquals(0, TimelineImagePanel.xAtTime(10_000_000, 1000, 10_000_000, 20_000_000))
        assertEquals(1000, TimelineImagePanel.xAtTime(20_000_000, 1000, 10_000_000, 20_000_000))
        assertEquals(500, TimelineImagePanel.xAtTime(15_000_000, 1000, 10_000_000, 20_000_000))
        assertEquals(-500, TimelineImagePanel.xAtTime(5_000_000, 1000, 10_000_000, 20_000_000))
    }

    @Test
    fun `zoomWindow zooms in around anchor and clamps`() {
        val (s, e) = TimelineImagePanel.zoomWindow(0, 60_000_000, 0.5, 0.5, 60_000_000)
        assertEquals(15_000_000L, s)
        assertEquals(45_000_000L, e)
    }

    @Test
    fun `zoomWindow out clamps to full duration`() {
        val (s, e) = TimelineImagePanel.zoomWindow(15_000_000, 45_000_000, 2.0, 0.5, 60_000_000)
        assertEquals(0L, s)
        assertEquals(60_000_000L, e)
    }

    @Test
    fun `zoomWindow respects minimum width`() {
        val (s, e) = TimelineImagePanel.zoomWindow(0, 60_000_000, 0.0001, 0.0, 60_000_000)
        assertEquals(100_000L, e - s)
    }

    @Test
    fun `panWindow shifts and clamps`() {
        assertEquals(15_000_000L to 25_000_000L, TimelineImagePanel.panWindow(10_000_000, 20_000_000, 5_000_000, 60_000_000))
        assertEquals(50_000_000L to 60_000_000L, TimelineImagePanel.panWindow(55_000_000, 65_000_000, 5_000_000, 60_000_000))
        assertEquals(0L to 10_000_000L, TimelineImagePanel.panWindow(5_000_000, 15_000_000, -10_000_000, 60_000_000))
    }

    @Test
    fun `computeTicks produces nice 10s interval for full minute`() {
        val ticks = TimelineImagePanel.computeTicks(0, 60_000_000, 800, 80)
        assertEquals("00:00", ticks.first().label)
        assertEquals("00:10", ticks[1].label)
        assertTrue(ticks.size >= 6)
        assertTrue(ticks[1].xPixel > ticks[0].xPixel)
    }

    @Test
    fun `computeTicks empty for bad input`() {
        assertTrue(TimelineImagePanel.computeTicks(0, 0, 800, 80).isEmpty())
        assertTrue(TimelineImagePanel.computeTicks(0, 60_000_000, 0, 80).isEmpty())
    }
}
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.TimelineImagePanelTest"` → compile/assertion errors.

- [ ] **Step 3: Rewrite TimelineImagePanel.kt** — replace the file with:

```kotlin
package com.github.audioplayer

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * 波形/スペクトラム画像を表示窓 [viewStartMicros, viewEndMicros] に対応して描画する。
 * 再生位置ライン・再生済み塗り・時間ルーラーを重ね、クリック/ドラッグで [onSeek] に絶対時間を通知する。
 */
class TimelineImagePanel(
    private val onSeek: (Long) -> Unit,
) : JComponent() {
    var image: BufferedImage? = null
        set(value) { field = value; repaint() }

    var placeholderText: String? = null
        set(value) { field = value; repaint() }

    var durationMicros: Long = 0
        set(value) { field = value; repaint() }

    var positionMicros: Long = 0
        set(value) { field = value; repaint() }

    var viewStartMicros: Long = 0
        set(value) { field = value; repaint() }

    var viewEndMicros: Long = 0
        set(value) { field = value; repaint() }

    init {
        isOpaque = false
        val mouse =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = handle(e.x)

                override fun mouseDragged(e: MouseEvent) = handle(e.x)
            }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun handle(x: Int) {
        if (viewEndMicros <= viewStartMicros || width <= 0) return
        onSeek(timeAtX(x, width, viewStartMicros, viewEndMicros))
    }

    override fun getMinimumSize(): Dimension = Dimension(50, 50)

    override fun getPreferredSize(): Dimension = Dimension(800, 200)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val img = image
        if (img != null && width > 0 && height > 0) {
            g.drawImage(img, 0, 0, width, height, null)
        } else {
            val text = placeholderText
            if (text != null) {
                g.color = JBColor.foreground()
                val fm = g.fontMetrics
                g.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent) / 2)
            }
        }

        val vs = viewStartMicros
        val ve = viewEndMicros
        if (ve <= vs || width <= 0) return

        if (positionMicros > vs) {
            val px = xAtTime(positionMicros.coerceAtMost(ve), width, vs, ve).coerceIn(0, width)
            g.color = PROGRESS_COLOR
            g.fillRect(0, 0, px, height)
        }

        g.color = RULER_COLOR
        for (tick in computeTicks(vs, ve, width, 80)) {
            g.drawLine(tick.xPixel, height - RULER_HEIGHT, tick.xPixel, height)
            g.drawString(tick.label, tick.xPixel + 2, height - 3)
        }

        if (positionMicros in vs..ve) {
            val x = xAtTime(positionMicros, width, vs, ve)
            g.color = PLAYHEAD_COLOR
            g.fillRect(x, 0, 2, height)
        }
    }

    data class Tick(
        val xPixel: Int,
        val label: String,
    )

    companion object {
        private const val RULER_HEIGHT = 10
        private val PLAYHEAD_COLOR = JBColor(Color(0xFF, 0x33, 0x33), Color(0xFF, 0x55, 0x55))
        private val PROGRESS_COLOR = Color(0x44, 0x88, 0xCC, 0x40)
        private val RULER_COLOR = JBColor(Color(0x88, 0x88, 0x88), Color(0xAA, 0xAA, 0xAA))

        private val NICE_INTERVALS =
            listOf(
                100_000L, 200_000L, 500_000L,
                1_000_000L, 2_000_000L, 5_000_000L,
                10_000_000L, 15_000_000L, 30_000_000L,
                60_000_000L, 120_000_000L, 300_000_000L, 600_000_000L,
                1_800_000_000L, 3_600_000_000L,
            )

        fun timeAtX(
            x: Int,
            width: Int,
            viewStart: Long,
            viewEnd: Long,
        ): Long {
            if (width <= 0 || viewEnd <= viewStart) return viewStart
            val clampedX = x.coerceIn(0, width)
            return viewStart + (clampedX.toLong() * (viewEnd - viewStart)) / width
        }

        fun xAtTime(
            t: Long,
            width: Int,
            viewStart: Long,
            viewEnd: Long,
        ): Int {
            if (width <= 0 || viewEnd <= viewStart) return 0
            return (((t - viewStart) * width) / (viewEnd - viewStart)).toInt()
        }

        fun zoomWindow(
            viewStart: Long,
            viewEnd: Long,
            factor: Double,
            anchorFraction: Double,
            duration: Long,
        ): Pair<Long, Long> {
            val minWidth = 100_000L
            val curWidth = (viewEnd - viewStart).coerceAtLeast(1)
            val anchorTime = viewStart + (curWidth * anchorFraction).toLong()
            val maxWidth = duration.coerceAtLeast(minWidth)
            val newWidth = (curWidth * factor).toLong().coerceIn(minWidth, maxWidth)
            var newStart = anchorTime - (newWidth * anchorFraction).toLong()
            var newEnd = newStart + newWidth
            if (newStart < 0) {
                newStart = 0
                newEnd = newWidth
            }
            if (newEnd > duration) {
                newEnd = duration
                newStart = (duration - newWidth).coerceAtLeast(0)
            }
            return newStart to newEnd
        }

        fun panWindow(
            viewStart: Long,
            viewEnd: Long,
            deltaMicros: Long,
            duration: Long,
        ): Pair<Long, Long> {
            val w = viewEnd - viewStart
            val newStart = (viewStart + deltaMicros).coerceIn(0, (duration - w).coerceAtLeast(0))
            return newStart to (newStart + w)
        }

        fun computeTicks(
            viewStart: Long,
            viewEnd: Long,
            width: Int,
            targetSpacingPx: Int,
        ): List<Tick> {
            if (width <= 0 || viewEnd <= viewStart || targetSpacingPx <= 0) return emptyList()
            val windowMicros = viewEnd - viewStart
            val approxTicks = (width / targetSpacingPx).coerceAtLeast(1)
            val rawInterval = windowMicros / approxTicks
            val interval = NICE_INTERVALS.firstOrNull { it >= rawInterval } ?: NICE_INTERVALS.last()
            val ticks = mutableListOf<Tick>()
            var t = ((viewStart + interval - 1) / interval) * interval
            while (t <= viewEnd) {
                ticks.add(Tick(xAtTime(t, width, viewStart, viewEnd), formatTick(t, interval)))
                t += interval
            }
            return ticks
        }

        private fun formatTick(
            micros: Long,
            interval: Long,
        ): String {
            val totalSec = micros / 1_000_000
            return if (interval < 1_000_000) {
                "${AudioPlayerService.formatTime(totalSec)}.${(micros % 1_000_000) / 100_000}"
            } else {
                AudioPlayerService.formatTime(totalSec)
            }
        }
    }
}
```

- [ ] **Step 4: Verify pass** — `./gradlew test --tests "com.github.audioplayer.TimelineImagePanelTest"` → PASS (9 tests). Then `./gradlew buildPlugin` (NOTE: this will FAIL to compile AudioPlayerPanel because `timeAtX`/`xAtTime` signatures changed and the panel still uses window props that don't exist yet — that is fine; Task 4 fixes the panel. If buildPlugin must pass now, instead just run the unit test. Do NOT edit AudioPlayerPanel in this task.) Actually: the panel currently does NOT call `timeAtX`/`xAtTime` (only the panel sets `positionMicros`/`durationMicros`). Confirm by grep: `grep -n "timeAtX\|xAtTime" src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt` returns nothing. So `./gradlew buildPlugin` SHOULD still compile (the panel only sets properties that still exist). Run `./gradlew buildPlugin` and confirm success; if it fails due to the panel, STOP and report.

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/TimelineImagePanel.kt \
        src/test/kotlin/com/github/audioplayer/TimelineImagePanelTest.kt
git commit -m "feat: TimelineImagePanelを表示窓モデルにしルーラー/再生済み塗りを追加"
```

---

## Task 2: AudioAnalyzer のセグメント生成＋L/R分離

**Files:** Modify `AudioAnalyzer.kt`; Modify `AudioAnalyzerTest.kt`.

- [ ] **Step 1: Failing tests** — add to `AudioAnalyzerTest.kt` before final `}`:

```kotlin
    @Test
    fun `buildWaveformCommand adds seek duration and split channels`() {
        val cmd =
            AudioAnalyzer.buildWaveformCommand(
                ffmpegPath = "/bin/ffmpeg",
                inputPath = "/tmp/a.mp3",
                outputPath = "/tmp/w.png",
                width = 800,
                height = 200,
                startSec = 10.0,
                lenSec = 5.0,
                splitChannels = true,
            )
        val ss = cmd.indexOf("-ss")
        val t = cmd.indexOf("-t")
        val i = cmd.indexOf("-i")
        assertTrue(ss in 0 until i)
        assertTrue(t in 0 until i)
        assertEquals("10.0", cmd[ss + 1])
        assertEquals("5.0", cmd[t + 1])
        assertTrue(cmd.any { it.contains("split_channels=1") })
    }

    @Test
    fun `buildWaveformCommand without segment omits seek`() {
        val cmd =
            AudioAnalyzer.buildWaveformCommand("/bin/ffmpeg", "/tmp/a.mp3", "/tmp/w.png", 800, 200)
        assertFalse(cmd.contains("-ss"))
        assertFalse(cmd.contains("-t"))
        assertFalse(cmd.any { it.contains("split_channels") })
    }

    @Test
    fun `buildSpectrumCommand adds seek duration`() {
        val cmd =
            AudioAnalyzer.buildSpectrumCommand("/bin/ffmpeg", "/tmp/a.mp3", "/tmp/s.png", 800, 200, startSec = 3.0, lenSec = 2.0)
        assertEquals("3.0", cmd[cmd.indexOf("-ss") + 1])
        assertEquals("2.0", cmd[cmd.indexOf("-t") + 1])
    }
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.AudioAnalyzerTest"` → compile error.

- [ ] **Step 3: Implement** — replace the command builders and generate methods in `AudioAnalyzer.kt`. Replace `buildWaveformCommand`/`buildSpectrumCommand` with:

```kotlin
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
            if (startSec != null) { add("-ss"); add(startSec.toString()) }
            if (lenSec != null) { add("-t"); add(lenSec.toString()) }
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
            if (startSec != null) { add("-ss"); add(startSec.toString()) }
            if (lenSec != null) { add("-t"); add(lenSec.toString()) }
            add("-i")
            add(inputPath)
            add("-filter_complex")
            add("showspectrumpic=s=${width}x$height")
            add("-frames:v")
            add("1")
            add("-y")
            add(outputPath)
        }
```

Update `generateWaveformImage`/`generateSpectrumImage` to forward the new optional params, and update the private `generateImage` to accept and use them. Specifically:

```kotlin
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
```

(The private `generateImage(file, width, height, commandBuilder)` signature stays the same — `commandBuilder: (String, String, String, Int, Int) -> List<String>` — these lambdas close over the segment params. Keep the existing `generateImage` body unchanged.)

- [ ] **Step 4: Verify pass + build** — `./gradlew test`, `./gradlew buildPlugin`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioAnalyzer.kt \
        src/test/kotlin/com/github/audioplayer/AudioAnalyzerTest.kt
git commit -m "feat: AudioAnalyzerに区間生成(-ss/-t)とL/R分離(split_channels)を追加"
```

---

## Task 3: 設定 waveformSplitChannels

**Files:** Modify `AudioPlayerSettings.kt`; Modify `AudioPlayerSettingsTest.kt`.

- [ ] **Step 1: Failing test** — add to `AudioPlayerSettingsTest.kt`:

```kotlin
    @Test
    fun `default waveformSplitChannels is false`() {
        assertFalse(AudioPlayerSettings.SettingsState().waveformSplitChannels)
    }
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.AudioPlayerSettingsTest"` → compile error.

- [ ] **Step 3: Implement** — add field to `SettingsState`: after `showVisualizer: Boolean = true,` add `var waveformSplitChannels: Boolean = false,`.

- [ ] **Step 4: Verify pass** — `./gradlew test --tests "com.github.audioplayer.AudioPlayerSettingsTest"` → PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerSettings.kt \
        src/test/kotlin/com/github/audioplayer/AudioPlayerSettingsTest.kt
git commit -m "feat: 波形のL/R分離表示の設定を追加"
```

---

## Task 4: AudioPlayerPanel 統合（窓・ズーム・スクロール・分離トグル）

**Files:** Modify `AudioPlayerPanel.kt`. `runIde` 実行不可 → `buildPlugin`+`test` で確認。

Read the current `AudioPlayerPanel.kt` first. Implement:

- [ ] **Step 1: Add window state fields + scrollbar + buttons** — add fields:

```kotlin
    private var viewStartMicros = 0L
    private var viewEndMicros = 0L
    private val zoomInButton = JButton("＋")
    private val zoomOutButton = JButton("−")
    private val zoomFitButton = JButton("全体")
    private val splitChannelsToggle = JToggleButton("L/R分離")
    private val scrollBar = JScrollBar(JScrollBar.HORIZONTAL)
```

- [ ] **Step 2: Add controls to analyze panel** — in `createAnalyzePanel()`, add to the existing `buttonPanel` (after Waveform/Spectrum/saveImage): `add(zoomOutButton); add(zoomInButton); add(zoomFitButton); add(splitChannelsToggle)`. And add the scroll bar to the panel SOUTH: in the returned `JPanel(BorderLayout())`, `add(scrollBar, BorderLayout.SOUTH)`. Set tooltips. Initialize `splitChannelsToggle.isSelected = settingsState.waveformSplitChannels`. Initialize `scrollBar.isEnabled = false`.

- [ ] **Step 3: Window helpers** — add a field `private var isSyncingScrollBar = false` and these methods. The scrollbar uses a unit of 1000μs (=1ms) per step so the Int range covers multi-hour files:

```kotlin
    private var isSyncingScrollBar = false

    private fun setWindow(
        start: Long,
        end: Long,
    ) {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        viewStartMicros = start.coerceIn(0, total)
        viewEndMicros = end.coerceIn(viewStartMicros + 1, total)
        timelinePanel.viewStartMicros = viewStartMicros
        timelinePanel.viewEndMicros = viewEndMicros
        syncScrollBar()
        loadVisualization()
    }

    private fun anchorFraction(): Double {
        val w = (viewEndMicros - viewStartMicros).coerceAtLeast(1)
        val pos = playerService.currentMicroseconds
        return if (pos in viewStartMicros..viewEndMicros) {
            (pos - viewStartMicros).toDouble() / w
        } else {
            0.5
        }
    }

    private fun syncScrollBar() {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        val unit = 1000
        val extent = ((viewEndMicros - viewStartMicros) / unit).toInt().coerceAtLeast(1)
        val max = (total / unit).toInt().coerceAtLeast(1)
        val value = (viewStartMicros / unit).toInt()
        isSyncingScrollBar = true
        scrollBar.setValues(value, extent, 0, max)
        scrollBar.isEnabled = extent < max
        isSyncingScrollBar = false
    }
```

- [ ] **Step 4: Wire buttons/scrollbar/toggle** — in `setupListeners()` add:

```kotlin
        zoomInButton.addActionListener {
            val (s, e) = TimelineImagePanel.zoomWindow(viewStartMicros, viewEndMicros, 0.5, anchorFraction(), playerService.totalMicroseconds)
            setWindow(s, e)
        }
        zoomOutButton.addActionListener {
            val (s, e) = TimelineImagePanel.zoomWindow(viewStartMicros, viewEndMicros, 2.0, anchorFraction(), playerService.totalMicroseconds)
            setWindow(s, e)
        }
        zoomFitButton.addActionListener {
            setWindow(0, playerService.totalMicroseconds)
        }
        splitChannelsToggle.addActionListener {
            settingsState.waveformSplitChannels = splitChannelsToggle.isSelected
            loadVisualization()
        }
        scrollBar.addAdjustmentListener {
            if (isSyncingScrollBar) return@addAdjustmentListener
            val unit = 1000L
            val newStart = scrollBar.value.toLong() * unit
            val w = viewEndMicros - viewStartMicros
            viewStartMicros = newStart.coerceIn(0, (playerService.totalMicroseconds - w).coerceAtLeast(0))
            viewEndMicros = viewStartMicros + w
            timelinePanel.viewStartMicros = viewStartMicros
            timelinePanel.viewEndMicros = viewEndMicros
            loadVisualization()
        }
```

- [ ] **Step 5: Initialize window on load + segment-aware loadVisualization** — in `loadFile()`'s `invokeLater`, after `timelinePanel.durationMicros = playerService.totalMicroseconds`, add window init:

```kotlin
                viewStartMicros = 0
                viewEndMicros = playerService.totalMicroseconds
                timelinePanel.viewStartMicros = viewStartMicros
                timelinePanel.viewEndMicros = viewEndMicros
                syncScrollBar()
```

Replace `loadVisualization()` so it generates the current window segment with split-channels for waveform:

```kotlin
    private fun loadVisualization() {
        val requestId = ++visualizationRequestId
        val view = settingsState.defaultView
        val isSpectrum = view == "spectrum"
        val total = playerService.totalMicroseconds
        val full = viewStartMicros <= 0 && (viewEndMicros >= total || viewEndMicros <= 0)
        val startSec = if (full) null else viewStartMicros / 1_000_000.0
        val lenSec = if (full) null else (viewEndMicros - viewStartMicros) / 1_000_000.0
        val split = settingsState.waveformSplitChannels
        timelinePanel.image = null
        timelinePanel.placeholderText = if (isSpectrum) "Generating spectrum..." else "Generating waveform..."
        Thread {
            val img: BufferedImage? =
                if (isSpectrum) {
                    AudioAnalyzer.generateSpectrumImage(File(file.path), 800, 200, startSec, lenSec)
                } else {
                    AudioAnalyzer.generateWaveformImage(File(file.path), 800, 200, startSec, lenSec, split)
                }
            SwingUtilities.invokeLater {
                if (requestId != visualizationRequestId) return@invokeLater
                if (img != null) {
                    timelinePanel.placeholderText = null
                    timelinePanel.image = img
                } else {
                    timelinePanel.placeholderText = "Failed to generate (ffmpeg required)"
                }
            }
        }.start()
    }
```

(Note: `timelinePanel.durationMicros` is set in loadFile; the panel no longer needs it for mapping but keep it set.)

- [ ] **Step 6: Verify build + tests** — `./gradlew buildPlugin` (success), `./gradlew test` (full green).

- [ ] **Step 7: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: 波形のズーム/スクロール/全体表示とL/R分離トグルを統合"
```

---

## 完了条件
- `./gradlew test` 緑、`./gradlew buildPlugin` 成功、`./gradlew ktlintCheck` 違反なし。
- 窓座標変換・ズーム・パン・ルーラー目盛り・区間コマンドが純粋関数テストで検証済み。
- 既存の `buildWaveformCommand` 5引数呼び出しがデフォルト引数で維持。
