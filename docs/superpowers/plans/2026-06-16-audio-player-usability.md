# Audio Player 使いやすさ向上 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 波形/スペクトラムをインタラクティブにし、再生操作・初回セットアップ・リサイズ・表示切替を改善して JetBrains Audio Player プラグインの使いやすさを上げる。

**Architecture:** 静的な画像 `JLabel` を、画像を幅に合わせ拡縮し再生位置ラインを描画してクリックでシークできる独自 `JComponent`（`TimelineImagePanel`）に置き換える。座標↔時間の変換やシーク量計算は純粋関数として切り出し TDD でテストする。再生設定（音量・ループ・表示状態）は既存の `PersistentStateComponent` に永続化する。

**Tech Stack:** Kotlin 2.1, IntelliJ Platform Plugin SDK 2.x, Swing, javax.sound.sampled, ffmpeg (ProcessBuilder), JUnit 4, ktlint 1.5。

**設計仕様:** `docs/superpowers/specs/2026-06-16-audio-player-usability-design.md`

**共通の注意:**
- 各タスクのコミット前に `./gradlew ktlintFormat` を実行してフォーマットを整える。
- テスト実行は `./gradlew test`、ビルドは `./gradlew buildPlugin`、手動確認は `./gradlew runIde`。
- UI/ffmpeg 依存のコードはユニットテストできないため、純粋関数を TDD で、UI 結線は実装＋`runIde` での手動確認で進める（既存コードも UI 層にはテストがない方針）。

---

## File Structure

新規:
- `src/main/kotlin/com/github/audioplayer/TimelineImagePanel.kt` — 画像描画＋再生位置ライン＋クリックシークの独自コンポーネント。座標↔時間変換を companion の純粋関数として持つ。
- `src/test/kotlin/com/github/audioplayer/TimelineImagePanelTest.kt` — 座標↔時間変換のテスト。
- `src/test/kotlin/com/github/audioplayer/AudioPlayerSettingsTest.kt` — 設定の既定値テスト。

変更:
- `AudioPlayerSettings.kt` — 永続化フィールド追加。
- `AudioPlayerSettingsConfigurable.kt` — `apply()` が新フィールドを消さないよう修正。
- `AudioPlayerService.kt` — シーク量計算の純粋関数 `computeSeekTarget` を追加。
- `AudioPlayerServiceTest.kt` — `computeSeekTarget` のテスト追加。
- `AudioAnalyzer.kt` — `BufferedImage` を返す生成メソッド追加（既存 `ImageIcon` 版はそれを包む）。
- `AudioPlayerPanel.kt` — 画像コンポーネント置換、タイマー連動、波形自動生成、キー操作、設定読込保存、ビジュアライザ表示トグル、最小サイズ緩和、未検出通知。
- `src/main/resources/META-INF/plugin.xml` — 通知グループ登録。

---

## Task 1: 設定の永続化フィールドを追加

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/AudioPlayerSettings.kt`
- Modify: `src/main/kotlin/com/github/audioplayer/AudioPlayerSettingsConfigurable.kt`
- Test: `src/test/kotlin/com/github/audioplayer/AudioPlayerSettingsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/github/audioplayer/AudioPlayerSettingsTest.kt`:

```kotlin
package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioPlayerSettingsTest {
    @Test
    fun `default lastVolume is 80`() {
        assertEquals(80, AudioPlayerSettings.SettingsState().lastVolume)
    }

    @Test
    fun `default lastLooping is false`() {
        assertFalse(AudioPlayerSettings.SettingsState().lastLooping)
    }

    @Test
    fun `default defaultView is waveform`() {
        assertEquals("waveform", AudioPlayerSettings.SettingsState().defaultView)
    }

    @Test
    fun `default showVisualizer is true`() {
        assertTrue(AudioPlayerSettings.SettingsState().showVisualizer)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.github.audioplayer.AudioPlayerSettingsTest"`
Expected: コンパイルエラー（`lastVolume` 等が未定義）。

- [ ] **Step 3: Add fields to SettingsState**

`AudioPlayerSettings.kt` の `SettingsState` を以下に置き換える:

```kotlin
    data class SettingsState(
        var ffmpegPath: String = "",
        var ffprobePath: String = "",
        var lastVolume: Int = 80,
        var lastLooping: Boolean = false,
        var defaultView: String = "waveform",
        var showVisualizer: Boolean = true,
    )
```

- [ ] **Step 4: Fix Configurable.apply() so it does not wipe the new fields**

`AudioPlayerSettingsConfigurable.kt` の `apply()` を以下に置き換える（`loadState` で新規 `SettingsState` を作ると新フィールドが既定値に戻ってしまうため、既存 state を直接更新する）:

```kotlin
    override fun apply() {
        val state = AudioPlayerSettings.instance.state
        state.ffmpegPath = ffmpegPathField?.text.orEmpty()
        state.ffprobePath = ffprobePathField?.text.orEmpty()
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.github.audioplayer.AudioPlayerSettingsTest"`
Expected: PASS（4 件）。

- [ ] **Step 6: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerSettings.kt \
        src/main/kotlin/com/github/audioplayer/AudioPlayerSettingsConfigurable.kt \
        src/test/kotlin/com/github/audioplayer/AudioPlayerSettingsTest.kt
git commit -m "feat: 再生設定(音量/ループ/表示状態)の永続化フィールドを追加"
```

---

## Task 2: シーク量計算の純粋関数とキーボードシーク

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/AudioPlayerService.kt`
- Test: `src/test/kotlin/com/github/audioplayer/AudioPlayerServiceTest.kt`

- [ ] **Step 1: Write the failing test**

`AudioPlayerServiceTest.kt` に以下のテストを追加（既存テストの末尾、最後の `}` の直前）:

```kotlin
    @Test
    fun `computeSeekTarget adds positive delta`() {
        assertEquals(15_000_000L, AudioPlayerService.computeSeekTarget(10_000_000L, 5_000_000L, 60_000_000L))
    }

    @Test
    fun `computeSeekTarget clamps to zero on negative result`() {
        assertEquals(0L, AudioPlayerService.computeSeekTarget(3_000_000L, -5_000_000L, 60_000_000L))
    }

    @Test
    fun `computeSeekTarget clamps to total on overflow`() {
        assertEquals(60_000_000L, AudioPlayerService.computeSeekTarget(58_000_000L, 5_000_000L, 60_000_000L))
    }

    @Test
    fun `computeSeekTarget returns zero when total is zero`() {
        assertEquals(0L, AudioPlayerService.computeSeekTarget(0L, 5_000_000L, 0L))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.github.audioplayer.AudioPlayerServiceTest"`
Expected: コンパイルエラー（`computeSeekTarget` 未定義）。

- [ ] **Step 3: Implement the pure function**

`AudioPlayerService.kt` の `companion object` 内、`formatTime` の上に追加:

```kotlin
        fun computeSeekTarget(
            currentMicros: Long,
            deltaMicros: Long,
            totalMicros: Long,
        ): Long = (currentMicros + deltaMicros).coerceIn(0, totalMicros)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.github.audioplayer.AudioPlayerServiceTest"`
Expected: PASS（既存＋新規 4 件）。

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerService.kt \
        src/test/kotlin/com/github/audioplayer/AudioPlayerServiceTest.kt
git commit -m "feat: シーク量計算の純粋関数 computeSeekTarget を追加"
```

> 注: キーボードシークの UI 結線は Task 5 で `AudioPlayerPanel` を改修する際に `computeSeekTarget` を使って実装する。

---

## Task 3: AudioAnalyzer に BufferedImage 生成メソッドを追加

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/AudioAnalyzer.kt`

ユニットテストは ffmpeg と実ファイルが必要なため追加しない。既存の command builder テストが緑のままであることと、`./gradlew buildPlugin` のコンパイル成功で確認する。

- [ ] **Step 1: Add imports**

`AudioAnalyzer.kt` の import 群に追加:

```kotlin
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
```

- [ ] **Step 2: Add BufferedImage-returning public methods**

`generateWaveform` / `generateSpectrum`（既存）を、BufferedImage 版を包む形に置き換える。既存の 2 メソッドを以下に差し替える:

```kotlin
    fun generateWaveformImage(
        file: File,
        width: Int,
        height: Int,
    ): BufferedImage? = generateImage(file, width, height, ::buildWaveformCommand)

    fun generateSpectrumImage(
        file: File,
        width: Int,
        height: Int,
    ): BufferedImage? = generateImage(file, width, height, ::buildSpectrumCommand)

    fun generateWaveform(
        file: File,
        width: Int,
        height: Int,
    ): ImageIcon? = generateWaveformImage(file, width, height)?.let { ImageIcon(it) }

    fun generateSpectrum(
        file: File,
        width: Int,
        height: Int,
    ): ImageIcon? = generateSpectrumImage(file, width, height)?.let { ImageIcon(it) }
```

- [ ] **Step 3: Change generateImage to return BufferedImage**

`private fun generateImage(...)` の本体を以下に置き換える（戻り値型を `BufferedImage?` にし、`ImageIO.read` で読み、temp ファイルは finally で削除）:

```kotlin
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

        val outputFile = File.createTempFile("audioplayer_", ".png")

        return try {
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

            ImageIO.read(outputFile)
        } catch (e: Exception) {
            LOG.error("Failed to generate image", e)
            null
        } finally {
            outputFile.delete()
        }
    }
```

- [ ] **Step 4: Verify build and existing tests**

Run: `./gradlew test --tests "com.github.audioplayer.AudioAnalyzerTest"` then `./gradlew buildPlugin`
Expected: テスト PASS（command builder 2 件）、ビルド成功。

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioAnalyzer.kt
git commit -m "feat: AudioAnalyzer に BufferedImage を返す生成メソッドを追加"
```

---

## Task 4: TimelineImagePanel（描画＋クリックシーク）

**Files:**
- Create: `src/main/kotlin/com/github/audioplayer/TimelineImagePanel.kt`
- Test: `src/test/kotlin/com/github/audioplayer/TimelineImagePanelTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/github/audioplayer/TimelineImagePanelTest.kt`:

```kotlin
package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class TimelineImagePanelTest {
    private val duration = 60_000_000L

    @Test
    fun `timeAtX maps left edge to zero`() {
        assertEquals(0L, TimelineImagePanel.timeAtX(0, 1000, duration))
    }

    @Test
    fun `timeAtX maps right edge to duration`() {
        assertEquals(duration, TimelineImagePanel.timeAtX(1000, 1000, duration))
    }

    @Test
    fun `timeAtX maps midpoint to half duration`() {
        assertEquals(30_000_000L, TimelineImagePanel.timeAtX(500, 1000, duration))
    }

    @Test
    fun `timeAtX clamps negative x to zero`() {
        assertEquals(0L, TimelineImagePanel.timeAtX(-50, 1000, duration))
    }

    @Test
    fun `timeAtX clamps x beyond width to duration`() {
        assertEquals(duration, TimelineImagePanel.timeAtX(2000, 1000, duration))
    }

    @Test
    fun `timeAtX returns zero when width is zero`() {
        assertEquals(0L, TimelineImagePanel.timeAtX(500, 0, duration))
    }

    @Test
    fun `xAtTime maps zero to left edge`() {
        assertEquals(0, TimelineImagePanel.xAtTime(0, 1000, duration))
    }

    @Test
    fun `xAtTime maps midpoint`() {
        assertEquals(500, TimelineImagePanel.xAtTime(30_000_000L, 1000, duration))
    }

    @Test
    fun `xAtTime clamps full duration to last pixel`() {
        assertEquals(999, TimelineImagePanel.xAtTime(duration, 1000, duration))
    }

    @Test
    fun `xAtTime returns zero when duration is zero`() {
        assertEquals(0, TimelineImagePanel.xAtTime(100, 1000, 0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.github.audioplayer.TimelineImagePanelTest"`
Expected: コンパイルエラー（`TimelineImagePanel` 未定義）。

- [ ] **Step 3: Implement TimelineImagePanel**

Create `src/main/kotlin/com/github/audioplayer/TimelineImagePanel.kt`:

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
 * 波形/スペクトラム画像を幅に合わせて描画し、再生位置の縦ラインを重ねる。
 * クリック/ドラッグでその位置の時間を [onSeek] に通知する。
 * 最小サイズを小さく申告し、エディタ分割でのリサイズの妨げにならないようにする。
 */
class TimelineImagePanel(
    private val onSeek: (Long) -> Unit,
) : JComponent() {
    var image: BufferedImage? = null
        set(value) {
            field = value
            repaint()
        }

    var placeholderText: String? = null
        set(value) {
            field = value
            repaint()
        }

    var durationMicros: Long = 0
        set(value) {
            field = value
            repaint()
        }

    var positionMicros: Long = 0
        set(value) {
            field = value
            repaint()
        }

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
        if (durationMicros <= 0 || width <= 0) return
        onSeek(timeAtX(x, width, durationMicros))
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
        if (durationMicros > 0 && width > 0) {
            val x = xAtTime(positionMicros, width, durationMicros)
            g.color = PLAYHEAD_COLOR
            g.fillRect(x, 0, 2, height)
        }
    }

    companion object {
        private val PLAYHEAD_COLOR = JBColor(Color(0xFF, 0x33, 0x33), Color(0xFF, 0x55, 0x55))

        fun timeAtX(
            x: Int,
            width: Int,
            durationMicros: Long,
        ): Long {
            if (width <= 0) return 0
            val clampedX = x.coerceIn(0, width)
            return (clampedX.toLong() * durationMicros) / width
        }

        fun xAtTime(
            positionMicros: Long,
            width: Int,
            durationMicros: Long,
        ): Int {
            if (durationMicros <= 0 || width <= 0) return 0
            val clamped = positionMicros.coerceIn(0, durationMicros)
            return ((clamped * width) / durationMicros).toInt().coerceIn(0, width - 1)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.github.audioplayer.TimelineImagePanelTest"`
Expected: PASS（10 件）。

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/TimelineImagePanel.kt \
        src/test/kotlin/com/github/audioplayer/TimelineImagePanelTest.kt
git commit -m "feat: 再生位置ライン付きクリックシーク対応の TimelineImagePanel を追加"
```

---

## Task 5: AudioPlayerPanel に統合（波形自動生成・位置ライン・クリック/キーシーク・設定復元・リサイズ解消）

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt`

UI 結線のためユニットテストは行わず、`./gradlew runIde` で手動確認する。

- [ ] **Step 1: Add imports and replace imageLabel field**

`AudioPlayerPanel.kt` の import 群に追加:

```kotlin
import java.awt.image.BufferedImage
```

フィールド宣言の `imageLabel` を削除し、`TimelineImagePanel` に置き換える。`playerService` フィールド宣言の直後あたりに `timelinePanel` を、`topSplit`/`mainSplit`/`currentCenter` 用のフィールドも追加する:

```kotlin
    private val timelinePanel = TimelineImagePanel { micros -> seekToMicros(micros) }

    private lateinit var topSplit: JSplitPane
    private lateinit var mainSplit: JSplitPane
    private lateinit var currentCenter: JComponent
```

（`import javax.swing.JComponent` が無ければ `javax.swing.*` の wildcard import で既にカバーされている。`AudioPlayerPanel.kt` 先頭は `import javax.swing.*` のため追加不要。）

- [ ] **Step 2: Add a settings accessor helper**

`init {}` ブロックの上に追加:

```kotlin
    private val settingsState
        get() = AudioPlayerSettings.instance.state
```

- [ ] **Step 3: Wire persisted volume/loop into init**

`init {}` を以下に置き換える（設定の復元を `loadFile()` の前に行う）:

```kotlin
    init {
        border = JBUI.Borders.empty(8)
        background = JBColor.background()
        volumeSlider.value = settingsState.lastVolume
        volumeValueLabel.text = "${settingsState.lastVolume}%"
        loopButton.isSelected = settingsState.lastLooping
        setupUI()
        setupListeners()
        loadFile()
    }
```

- [ ] **Step 4: Lift topSplit/mainSplit to fields and relax minimum sizes in setupUI**

`setupUI()` を以下に置き換える（ローカル変数だった `topSplit`/`mainSplit` をフィールドに代入、`currentCenter` を導入、最小サイズを緩和して「壁」を解消）:

```kotlin
    private fun setupUI() {
        val infoPanel = createInfoPanel()
        val controlsPanel = createControlsPanel()

        infoPanel.minimumSize = Dimension(0, 0)
        controlsPanel.minimumSize = Dimension(0, 0)

        topSplit =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, infoPanel, controlsPanel).apply {
                resizeWeight = 0.5
                border = null
                isOpaque = false
                minimumSize = Dimension(0, 0)
            }

        val analyzePanel = createAnalyzePanel()
        analyzePanel.minimumSize = Dimension(0, 0)

        mainSplit =
            JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, analyzePanel).apply {
                resizeWeight = 0.5
                border = null
                isOpaque = false
                minimumSize = Dimension(0, 0)
            }

        currentCenter = if (settingsState.showVisualizer) mainSplit else topSplit
        add(currentCenter, BorderLayout.CENTER)

        addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    if (currentCenter === mainSplit) {
                        mainSplit.setDividerLocation(0.5)
                        topSplit.setDividerLocation(0.5)
                    }
                }
            },
        )
    }

    override fun getMinimumSize(): Dimension = Dimension(100, 100)
```

- [ ] **Step 5: Use timelinePanel in createAnalyzePanel**

`createAnalyzePanel()` 内の `imageLabel.apply { ... }` ブロックを削除し、`add(imageLabel, BorderLayout.CENTER)` を `add(timelinePanel, BorderLayout.CENTER)` に変更する。結果は以下:

```kotlin
    private fun createAnalyzePanel(): JPanel {
        analyzeWaveformButton.toolTipText = "Generate waveform image"
        analyzeSpectrumButton.toolTipText = "Generate spectrum image"

        val buttonPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false
                add(analyzeWaveformButton)
                add(analyzeSpectrumButton)
            }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(buttonPanel, BorderLayout.NORTH)
            add(timelinePanel, BorderLayout.CENTER)
        }
    }
```

- [ ] **Step 6: Add seek helpers and persist volume/loop in setupListeners**

`setupListeners()` 内の `volumeSlider.addChangeListener { ... }` を以下に置き換える（音量を設定に保存）:

```kotlin
        volumeSlider.addChangeListener {
            volumeValueLabel.text = "${volumeSlider.value}%"
            playerService.setVolume(volumeSlider.value.toFloat())
            settingsState.lastVolume = volumeSlider.value
        }
```

`loopButton.addActionListener { ... }` を以下に置き換える（ループ状態を保存）:

```kotlin
        loopButton.addActionListener {
            playerService.setLooping(loopButton.isSelected)
            settingsState.lastLooping = loopButton.isSelected
        }
```

`analyzeWaveformButton` / `analyzeSpectrumButton` の addActionListener 2 ブロックを以下に置き換える（`timelinePanel` を更新し、選択ビューを記憶。生成は共通メソッド `loadVisualization` を使う）:

```kotlin
        analyzeWaveformButton.addActionListener {
            settingsState.defaultView = "waveform"
            loadVisualization()
        }

        analyzeSpectrumButton.addActionListener {
            settingsState.defaultView = "spectrum"
            loadVisualization()
        }
```

`setupListeners()` の末尾（Space キー登録ブロックの後ろ、関数の閉じ括弧の手前）にキーボードシークを追加:

```kotlin
        registerSeekKey(KeyEvent.VK_LEFT, "seekBackward") { seekRelative(-5_000_000) }
        registerSeekKey(KeyEvent.VK_RIGHT, "seekForward") { seekRelative(5_000_000) }
        registerSeekKey(KeyEvent.VK_HOME, "seekStart") { seekToMicros(0) }
```

- [ ] **Step 7: Add helper methods**

`startPositionTimer()` の上に以下のメソッド群を追加:

```kotlin
    private fun registerSeekKey(
        keyCode: Int,
        actionKey: String,
        action: () -> Unit,
    ) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), actionKey)
        actionMap.put(
            actionKey,
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
            },
        )
    }

    private fun seekRelative(deltaMicros: Long) {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        seekToMicros(AudioPlayerService.computeSeekTarget(playerService.currentMicroseconds, deltaMicros, total))
    }

    private fun seekToMicros(micros: Long) {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        playerService.seek(micros)
        seekSlider.value = ((micros * 1000) / total).toInt()
        timelinePanel.positionMicros = micros
        updateTimeLabel(micros, total)
    }

    private fun loadVisualization() {
        val view = settingsState.defaultView
        val isSpectrum = view == "spectrum"
        timelinePanel.image = null
        timelinePanel.placeholderText = if (isSpectrum) "Generating spectrum..." else "Generating waveform..."
        Thread {
            val img: BufferedImage? =
                if (isSpectrum) {
                    AudioAnalyzer.generateSpectrumImage(File(file.path), 800, 200)
                } else {
                    AudioAnalyzer.generateWaveformImage(File(file.path), 800, 200)
                }
            SwingUtilities.invokeLater {
                timelinePanel.durationMicros = playerService.totalMicroseconds
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

- [ ] **Step 8: Update the position timer to move the playhead**

`startPositionTimer()` の `Timer(100) { ... }` 内、`seekSlider.value = ...` の行の直後に追加:

```kotlin
                    if (total > 0) {
                        seekSlider.value = ((current * 1000) / total).toInt()
                        timelinePanel.positionMicros = current
                    }
```

（既存の `if (total > 0) { seekSlider.value = ... }` ブロックをこの内容に置き換える。）

- [ ] **Step 9: Rewrite loadFile to set duration, restore loop, and auto-load visualization**

`loadFile()` を以下に置き換える:

```kotlin
    private fun loadFile() {
        statusLabel.text = "Loading..."
        Thread {
            val metadata = AudioProbe.probe(File(file.path))
            playerService.load(File(file.path))

            SwingUtilities.invokeLater {
                timelinePanel.durationMicros = playerService.totalMicroseconds
                if (playerService.totalMicroseconds > 0) {
                    statusLabel.text = ""
                    playerService.setVolume(volumeSlider.value.toFloat())
                    playerService.setLooping(loopButton.isSelected)
                } else if (statusLabel.text == "Loading...") {
                    statusLabel.text = "Failed to load audio file"
                }

                val ffmpegMissing = FfmpegPathUtil.findFfmpeg() == null
                val ffprobeMissing = FfmpegPathUtil.findFfprobe() == null
                settingsLink.isVisible = ffmpegMissing || ffprobeMissing

                updateTimeLabel(0, playerService.totalMicroseconds)
                updateInfoTable(metadata)

                if (settingsState.showVisualizer) {
                    loadVisualization()
                }
            }
        }.start()
    }
```

- [ ] **Step 10: Verify build and run**

Run: `./gradlew buildPlugin` then `./gradlew runIde`
Expected: ビルド成功。起動した IDE で音声ファイルを開くと、波形が自動表示され、再生中に縦ラインが動く。波形/スペクトラムをクリックするとその位置へジャンプ。←/→ で ±5 秒、Home で先頭へ。Waveform/Spectrum ボタンで表示が切り替わる。音量を変えて閉じ、別ファイルを開くと音量が復元される。テキストファイルとオーディオをエディタ分割で左右に並べ、オーディオ側を狭めても「壁」なく縮められる。

- [ ] **Step 11: Run full test suite**

Run: `./gradlew test`
Expected: 全テスト PASS。

- [ ] **Step 12: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: 波形のインタラクティブ化・キーシーク・設定復元・リサイズ改善を統合"
```

---

## Task 6: ビジュアライザ表示/非表示トグル

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt`

`./gradlew runIde` で手動確認する。

- [ ] **Step 1: Add the toggle field**

フィールド宣言群（`loopButton` の近く）に追加:

```kotlin
    private val visualizerToggle = JCheckBox("ビジュアライザを表示", true)
```

`import javax.swing.*` のため追加 import は不要。

- [ ] **Step 2: Initialize toggle state and add it to the controls panel**

`createControlsPanel()` 内、`centerPanel` を構築する `JPanel().apply { ... }` の `add(settingsLink)` の直後に追加:

```kotlin
                add(Box.createVerticalStrut(4))
                add(visualizerToggle.apply { alignmentX = java.awt.Component.CENTER_ALIGNMENT })
```

- [ ] **Step 3: Set initial state and listener in setupListeners**

`setupListeners()` の末尾（キーボードシーク登録の後ろ）に追加:

```kotlin
        visualizerToggle.isSelected = settingsState.showVisualizer
        visualizerToggle.addActionListener {
            applyVisualizerVisibility(visualizerToggle.isSelected)
        }
```

- [ ] **Step 4: Implement applyVisualizerVisibility**

`loadVisualization()` の下に追加:

```kotlin
    private fun applyVisualizerVisibility(show: Boolean) {
        settingsState.showVisualizer = show
        remove(currentCenter)
        currentCenter = if (show) mainSplit else topSplit
        add(currentCenter, BorderLayout.CENTER)
        revalidate()
        repaint()
        if (show) {
            if (timelinePanel.image == null && playerService.totalMicroseconds > 0) {
                loadVisualization()
            }
            SwingUtilities.invokeLater {
                mainSplit.setDividerLocation(0.5)
                topSplit.setDividerLocation(0.5)
            }
        }
    }
```

- [ ] **Step 5: Verify run**

Run: `./gradlew buildPlugin` then `./gradlew runIde`
Expected: ビルド成功。チェックを外すと下半分（ビジュアライザ）が消え、情報＋コントロールだけのコンパクト表示になる。再度チェックすると波形が再生成されて戻る。OFF のまま閉じて別ファイルを開いても OFF が維持され、その間は波形生成（ffmpeg 実行）が走らない。

- [ ] **Step 6: Run tests, format and commit**

```bash
./gradlew test
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: ビジュアライザの表示/非表示トグルを追加(コンパクト表示)"
```

---

## Task 7: ffmpeg/ffprobe 未検出時のバルーン通知

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt`

`./gradlew runIde`（ffmpeg を一時的に検出させない設定）で手動確認する。

- [ ] **Step 1: Register the notification group**

`plugin.xml` の `<extensions defaultExtensionNs="com.intellij">` 内、`<applicationConfigurable .../>` の後ろに追加:

```xml
        <notificationGroup
            id="Audio Player"
            displayType="BALLOON"/>
```

- [ ] **Step 2: Add imports to AudioPlayerPanel**

import 群に追加:

```kotlin
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
```

- [ ] **Step 3: Implement notifyDependencyMissing**

`applyVisualizerVisibility(...)` の下に追加:

```kotlin
    private fun notifyDependencyMissing(
        ffmpegMissing: Boolean,
        ffprobeMissing: Boolean,
    ) {
        val missing =
            buildList {
                if (ffmpegMissing) add("ffmpeg")
                if (ffprobeMissing) add("ffprobe")
            }.joinToString(" / ")
        val content =
            "$missing が見つかりません。再生・波形表示には ffmpeg、メタデータ表示には ffprobe が必要です。" +
                "インストール例 — macOS: brew install ffmpeg / Ubuntu: sudo apt install ffmpeg / Windows: winget install ffmpeg"
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Audio Player")
            .createNotification("Audio Player", content, NotificationType.WARNING)
            .addAction(
                NotificationAction.createSimple("設定を開く") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, AudioPlayerSettingsConfigurable::class.java)
                },
            ).notify(null)
    }
```

- [ ] **Step 4: Call it from loadFile when a dependency is missing**

`loadFile()` 内の `settingsLink.isVisible = ffmpegMissing || ffprobeMissing` の直後に追加:

```kotlin
                if (ffmpegMissing || ffprobeMissing) {
                    notifyDependencyMissing(ffmpegMissing, ffprobeMissing)
                }
```

- [ ] **Step 5: Verify run**

Run: `./gradlew buildPlugin` then `./gradlew runIde`
確認方法: 設定で ffmpeg/ffprobe パスに存在しないパスを入れる（または PATH を外す）と、音声ファイルを開いたときにバルーン通知が出て「設定を開く」アクションで設定画面が開く。正しく検出できる状態では通知は出ない。
Expected: ビルド成功、上記の挙動。

- [ ] **Step 6: Run tests, format and commit**

```bash
./gradlew test
./gradlew ktlintFormat
git add src/main/resources/META-INF/plugin.xml src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: ffmpeg/ffprobe 未検出時にインストール案内のバルーン通知を表示"
```

---

## 完了条件

- 全タスクのコミットが完了し `./gradlew test` が緑、`./gradlew buildPlugin` が成功。
- `runIde` で以下を確認: 波形の自動表示と再生位置ライン、クリック/←/→/Home シーク、音量・ループ・表示状態の永続化、ビジュアライザ表示トグル、エディタ分割でのリサイズ、未検出時の通知。
- ktlint 違反なし（`./gradlew ktlintCheck`）。
