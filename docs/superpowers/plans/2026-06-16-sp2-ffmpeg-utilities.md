# SP2: ffmpeg活用ユーティリティ Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** タグ表示＋コピー / LUFS測定 / 別フォーマット書き出し / 画像エクスポート の4ユーティリティを追加。

**Architecture:** 各機能はテスト可能な純粋コア（コマンド組み立て・出力解析・整形）＋薄いUI結線。純粋関数はTDD、UI/ffmpeg/IOは実装＋buildPluginで確認。

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Swing, JUnit 4, ktlint。

**設計仕様:** `docs/superpowers/specs/2026-06-16-sp2-ffmpeg-utilities-design.md`

**共通の注意:** コミット前 `./gradlew ktlintFormat`。`runIde` 実行しない。`build-signed.sh`/`build.gradle.kts` に触れない。各タスクは列挙ファイルのみ `git add`。各タスク完了時 `./gradlew test`＋`./gradlew buildPlugin` 緑を確認。

---

## Task 1: F1 タグ表示＋メタデータコピー

**Files:** Modify `AudioProbe.kt`, `AudioPlayerPanel.kt`; Test `AudioProbeTest.kt`, new `AudioPlayerPanelInfoTest.kt`.

- [ ] **Step 1: Failing tests for parseTags** — add to `AudioProbeTest.kt` before final `}`:

```kotlin
    @Test
    fun `parseTags extracts format tags lowercased`() {
        val json =
            """
            {
                "format": {
                    "tags": { "title": "Song", "ARTIST": "Band", "album": "Disc" }
                }
            }
            """.trimIndent()
        val tags = AudioProbe.parseTags(json)
        assertEquals("Song", tags["title"])
        assertEquals("Band", tags["artist"])
        assertEquals("Disc", tags["album"])
    }

    @Test
    fun `parseTags returns empty when no tags`() {
        assertTrue(AudioProbe.parseTags("""{ "format": {} }""").isEmpty())
    }
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.AudioProbeTest"` → compile error.

- [ ] **Step 3: Implement tags** — in `AudioProbe.kt`:
  (a) add field to `AudioMetadata`: after `durationSeconds: Double,` add `val tags: Map<String, String> = emptyMap(),`
  (b) in `parseJson(...)`, set `tags = parseTags(json)` in the `AudioMetadata(...)` constructor call (add `tags = parseTags(json),` before the closing `)`).
  (c) add functions:

```kotlin
    fun parseTags(json: String): Map<String, String> {
        val block = """"tags"\s*:\s*\{([^}]*)\}""".toRegex().find(json)?.groupValues?.get(1) ?: return emptyMap()
        val pair = """"([^"]+)"\s*:\s*"([^"]*)"""".toRegex()
        return pair.findAll(block).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    }
```

- [ ] **Step 4: Verify pass** — `./gradlew test --tests "com.github.audioplayer.AudioProbeTest"` → PASS.

- [ ] **Step 5: Failing tests for infoRowsToText** — create `src/test/kotlin/com/github/audioplayer/AudioPlayerPanelInfoTest.kt`:

```kotlin
package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioPlayerPanelInfoTest {
    @Test
    fun `infoRowsToText joins rows as tab-separated lines`() {
        val text =
            AudioPlayerPanel.infoRowsToText(
                listOf("File" to "a.mp3", "Duration" to "01:00"),
            )
        assertEquals("File\ta.mp3\nDuration\t01:00", text)
    }

    @Test
    fun `defaultImageFileName composes base and view`() {
        assertEquals("song_waveform.png", AudioPlayerPanel.defaultImageFileName("song", "waveform"))
        assertEquals("song_spectrum.png", AudioPlayerPanel.defaultImageFileName("song", "spectrum"))
    }
}
```

- [ ] **Step 6: Verify fail** — `./gradlew test --tests "com.github.audioplayer.AudioPlayerPanelInfoTest"` → compile error.

- [ ] **Step 7: Add companion pure functions** — in `AudioPlayerPanel.kt`, add to its `companion object` (it already exists with `NOTIFICATION_GROUP_ID`/`dependencyNotificationShown`):

```kotlin
        fun infoRowsToText(rows: List<Pair<String, String>>): String = rows.joinToString("\n") { "${it.first}\t${it.second}" }

        fun defaultImageFileName(
            baseName: String,
            view: String,
        ): String = "${baseName}_$view.png"
```

- [ ] **Step 8: Display tag rows** — in `AudioPlayerPanel.kt`'s `updateInfoTable(metadata)`, after the existing rows are added (inside the `if (metadata != null) { ... }` block, at its end before the `}`), append:

```kotlin
            val tagLabels = listOf("title" to "Title", "artist" to "Artist", "album" to "Album", "date" to "Year", "genre" to "Genre", "track" to "Track")
            for ((key, label) in tagLabels) {
                metadata.tags[key]?.let { infoTableModel.addRow(arrayOf(label, it)) }
            }
```

- [ ] **Step 9: Add copy button + handler** — add imports to `AudioPlayerPanel.kt`:

```kotlin
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
```

Add a field near the other buttons:

```kotlin
    private val copyInfoButton = JButton("情報をコピー")
```

In `createInfoPanel()`, change it to add a SOUTH button row. Replace the `return JPanel(BorderLayout()).apply { ... add(JScrollPane(infoTable), BorderLayout.CENTER) }` with one that also adds a button row (this task adds only copyInfoButton; Task 2/3 will add more to the same row — define the row panel as a field so later tasks reuse it):

Add field:

```kotlin
    private val infoActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
```

In `createInfoPanel()`, before `return`, add `infoActionsPanel.add(copyInfoButton)`, and in the returned panel add `add(infoActionsPanel, BorderLayout.SOUTH)`.

Add handler in `setupListeners()`:

```kotlin
        copyInfoButton.addActionListener {
            val rows =
                (0 until infoTableModel.rowCount).map {
                    (infoTableModel.getValueAt(it, 0)?.toString() ?: "") to (infoTableModel.getValueAt(it, 1)?.toString() ?: "")
                }
            CopyPasteManager.getInstance().setContents(StringSelection(infoRowsToText(rows)))
        }
```

- [ ] **Step 10: Verify pass + build** — `./gradlew test` (full), `./gradlew buildPlugin`.

- [ ] **Step 11: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioProbe.kt \
        src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt \
        src/test/kotlin/com/github/audioplayer/AudioProbeTest.kt \
        src/test/kotlin/com/github/audioplayer/AudioPlayerPanelInfoTest.kt
git commit -m "feat: フォーマットタグの表示と情報のクリップボードコピーを追加"
```

---

## Task 2: F2 ラウドネス測定（LUFS）

**Files:** New `LoudnessAnalyzer.kt`, `LoudnessAnalyzerTest.kt`; Modify `AudioPlayerPanel.kt`.

- [ ] **Step 1: Failing tests** — create `src/test/kotlin/com/github/audioplayer/LoudnessAnalyzerTest.kt`:

```kotlin
package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class LoudnessAnalyzerTest {
    @Test
    fun `buildEbur128Command builds correct command`() {
        val cmd = LoudnessAnalyzer.buildEbur128Command("/bin/ffmpeg", "/tmp/a.mp3")
        assertEquals("/bin/ffmpeg", cmd[0])
        assertEquals("/tmp/a.mp3", cmd[cmd.indexOf("-i") + 1])
        assertTrue(cmd.contains("ebur128"))
        assertEquals("-", cmd.last())
    }

    @Test
    fun `parseIntegratedLufs takes the last I value`() {
        val output =
            """
            [Parsed_ebur128_0 @ 0x1] t: 1   M: -20.0 S:-120.7 I: -19.0 LUFS
            [Parsed_ebur128_0 @ 0x1] Summary:
              Integrated loudness:
                I:         -14.2 LUFS
                Threshold: -24.7 LUFS
            """.trimIndent()
        assertEquals("-14.2 LUFS", LoudnessAnalyzer.parseIntegratedLufs(output))
    }

    @Test
    fun `parseIntegratedLufs returns null when absent`() {
        assertNull(LoudnessAnalyzer.parseIntegratedLufs("no loudness here"))
    }
}
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.LoudnessAnalyzerTest"` → compile error.

- [ ] **Step 3: Implement** — create `src/main/kotlin/com/github/audioplayer/LoudnessAnalyzer.kt`:

```kotlin
package com.github.audioplayer

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object LoudnessAnalyzer {
    private val LOG = Logger.getInstance(LoudnessAnalyzer::class.java)

    fun buildEbur128Command(
        ffmpeg: String,
        input: String,
    ): List<String> =
        listOf(ffmpeg, "-hide_banner", "-i", input, "-filter_complex", "ebur128", "-f", "null", "-")

    fun parseIntegratedLufs(output: String): String? {
        val matches = """I:\s*(-?\d+(?:\.\d+)?)\s*LUFS""".toRegex().findAll(output).toList()
        val value = matches.lastOrNull()?.groupValues?.get(1) ?: return null
        return "$value LUFS"
    }

    fun measure(file: File): String? {
        val ffmpeg = FfmpegPathUtil.findFfmpeg() ?: return null
        return try {
            val process =
                ProcessBuilder(buildEbur128Command(ffmpeg, file.absolutePath))
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            parseIntegratedLufs(output)
        } catch (e: Exception) {
            LOG.error("Loudness measurement failed", e)
            null
        }
    }
}
```

- [ ] **Step 4: Verify pass** — `./gradlew test --tests "com.github.audioplayer.LoudnessAnalyzerTest"` → PASS.

- [ ] **Step 5: Add LUFS button + handler** — in `AudioPlayerPanel.kt` add field:

```kotlin
    private val measureLufsButton = JButton("ラウドネス測定")
```

In `createInfoPanel()`, add to the actions row: `infoActionsPanel.add(measureLufsButton)`.

Add a helper to set/replace an info row (place near `updateInfoTable`):

```kotlin
    private fun setInfoRow(
        label: String,
        value: String,
    ) {
        for (i in 0 until infoTableModel.rowCount) {
            if (infoTableModel.getValueAt(i, 0) == label) {
                infoTableModel.setValueAt(value, i, 1)
                return
            }
        }
        infoTableModel.addRow(arrayOf(label, value))
    }
```

Add handler in `setupListeners()`:

```kotlin
        measureLufsButton.addActionListener {
            measureLufsButton.isEnabled = false
            setInfoRow("Loudness", "Measuring...")
            Thread {
                val result = LoudnessAnalyzer.measure(File(file.path))
                SwingUtilities.invokeLater {
                    setInfoRow("Loudness", result ?: "測定不可 (ffmpeg required)")
                    measureLufsButton.isEnabled = true
                }
            }.start()
        }
```

- [ ] **Step 6: Verify + build** — `./gradlew test`, `./gradlew buildPlugin`.

- [ ] **Step 7: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/LoudnessAnalyzer.kt \
        src/test/kotlin/com/github/audioplayer/LoudnessAnalyzerTest.kt \
        src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: ebur128による統合ラウドネス(LUFS)測定を追加"
```

---

## Task 3: F3 別フォーマットで書き出し

**Files:** Modify `AudioConverter.kt`, `AudioPlayerPanel.kt`; Test `AudioConverterTest.kt`.

- [ ] **Step 1: Failing test** — add to `AudioConverterTest.kt` before final `}`:

```kotlin
    @Test
    fun `buildExportCommand builds ffmpeg convert command`() {
        val cmd = AudioConverter.buildExportCommand("/bin/ffmpeg", "/tmp/in.wav", "/tmp/out.mp3")
        assertEquals(listOf("/bin/ffmpeg", "-i", "/tmp/in.wav", "-y", "/tmp/out.mp3"), cmd)
    }
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.AudioConverterTest"` → compile error.

- [ ] **Step 3: Implement** — add to `AudioConverter.kt` object:

```kotlin
    fun buildExportCommand(
        ffmpeg: String,
        input: String,
        output: String,
    ): List<String> = listOf(ffmpeg, "-i", input, "-y", output)

    fun export(
        input: File,
        output: File,
    ): Boolean {
        val ffmpeg = FfmpegPathUtil.findFfmpeg() ?: return false
        return try {
            val process =
                ProcessBuilder(buildExportCommand(ffmpeg, input.absolutePath, output.absolutePath))
                    .redirectErrorStream(true)
                    .start()
            val output2 = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (code != 0) LOG.error("ffmpeg export failed ($code): $output2")
            code == 0
        } catch (e: Exception) {
            LOG.error("Export failed", e)
            false
        }
    }
```

- [ ] **Step 4: Verify pass** — `./gradlew test --tests "com.github.audioplayer.AudioConverterTest"` → PASS.

- [ ] **Step 5: Add export button + notify helper + handler** — in `AudioPlayerPanel.kt` add imports if missing:

```kotlin
import javax.swing.JFileChooser
```

Add field:

```kotlin
    private val exportAudioButton = JButton("別形式で書き出し")
```

Add `infoActionsPanel.add(exportAudioButton)` in `createInfoPanel()`.

Add a notify helper (place near `notifyDependencyMissing`):

```kotlin
    private fun notifyUser(
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            ?.createNotification("Audio Player", content, type)
            ?.notify(null)
    }
```

Add handler in `setupListeners()`:

```kotlin
        exportAudioButton.addActionListener {
            val chooser = JFileChooser()
            chooser.selectedFile = File("${File(file.path).nameWithoutExtension}.mp3")
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return@addActionListener
            val output = chooser.selectedFile
            exportAudioButton.isEnabled = false
            Thread {
                val ok = AudioConverter.export(File(file.path), output)
                SwingUtilities.invokeLater {
                    notifyUser(
                        if (ok) "書き出し完了: ${output.name}" else "書き出しに失敗しました (ffmpeg required)",
                        if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                    )
                    exportAudioButton.isEnabled = true
                }
            }.start()
        }
```

- [ ] **Step 6: Verify + build** — `./gradlew test`, `./gradlew buildPlugin`.

- [ ] **Step 7: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioConverter.kt \
        src/test/kotlin/com/github/audioplayer/AudioConverterTest.kt \
        src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: ffmpegによる別フォーマット書き出しを追加"
```

---

## Task 4: F4 画像エクスポート

**Files:** Modify `AudioPlayerPanel.kt`. (`defaultImageFileName` companion fn + its test were added in Task 1.)

- [ ] **Step 1: Add imports** — in `AudioPlayerPanel.kt` add if missing:

```kotlin
import javax.imageio.ImageIO
```

- [ ] **Step 2: Add save-image button** — add field:

```kotlin
    private val saveImageButton = JButton("画像を保存")
```

In `createAnalyzePanel()`, add it to the existing `buttonPanel` (the `FlowLayout` row with Waveform/Spectrum): `add(saveImageButton)` after the spectrum button.

- [ ] **Step 3: Add handler** — in `setupListeners()`:

```kotlin
        saveImageButton.addActionListener {
            val img = timelinePanel.image
            if (img == null) {
                notifyUser("保存する画像がありません。先に波形/スペクトラムを生成してください。", NotificationType.WARNING)
                return@addActionListener
            }
            val chooser = JFileChooser()
            chooser.selectedFile = File(defaultImageFileName(File(file.path).nameWithoutExtension, settingsState.defaultView))
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return@addActionListener
            val out = chooser.selectedFile
            Thread {
                val ok =
                    try {
                        ImageIO.write(img, "png", out)
                    } catch (e: Exception) {
                        false
                    }
                SwingUtilities.invokeLater {
                    notifyUser(
                        if (ok) "画像を保存しました: ${out.name}" else "画像の保存に失敗しました",
                        if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                    )
                }
            }.start()
        }
```

- [ ] **Step 4: Verify + build** — `./gradlew test` (full, green), `./gradlew buildPlugin`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "feat: 表示中の波形/スペクトラム画像のPNG保存を追加"
```

---

## 完了条件
- `./gradlew test` 緑、`./gradlew buildPlugin` 成功、`./gradlew ktlintCheck` 違反なし。
- 純粋関数（parseTags / parseIntegratedLufs / buildEbur128Command / buildExportCommand / infoRowsToText / defaultImageFileName）がテスト済み。
