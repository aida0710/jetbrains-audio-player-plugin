# SP1: バグ予防・小改善 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Windows で ffmpeg/ffprobe を自動検出できるようにし、`loadVisualization` の連打時の表示取り違えを request-token で解消する。

**Architecture:** `FfmpegPathUtil` の OS 依存ロジック（OS判定・`where`/`which` 選択・既知パス一覧）を純粋関数に切り出して TDD でテスト、`autoDetect` をそれらで OS 分岐に書き換える。`AudioPlayerPanel` の `loadVisualization` に単調増加リクエストIDのガードを足す。

**Tech Stack:** Kotlin, IntelliJ Platform SDK, JUnit 4, ktlint。

**設計仕様:** `docs/superpowers/specs/2026-06-16-sp1-bugfix-and-windows-ffmpeg-design.md`

**共通の注意:**
- コミット前に `./gradlew ktlintFormat`。テストは `./gradlew test`、ビルドは `./gradlew buildPlugin`。
- `runIde` は実行しない（GUIブロッキング）。
- `build-signed.sh` / `build.gradle.kts` の作業ツリー変更には触れない。各タスクは列挙したファイルのみ `git add`。

---

## Task 1: OS依存ロジックの純粋関数化（TDD）

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/FfmpegPathUtil.kt`
- Test: `src/test/kotlin/com/github/audioplayer/FfmpegPathUtilTest.kt`

- [ ] **Step 1: Write failing tests** — add to `FfmpegPathUtilTest.kt` (before the final `}`):

```kotlin
    @Test
    fun `isWindowsOs detects windows`() {
        assertTrue(FfmpegPathUtil.isWindowsOs("Windows 11"))
        assertTrue(FfmpegPathUtil.isWindowsOs("windows 10"))
    }

    @Test
    fun `isWindowsOs false for unix`() {
        assertFalse(FfmpegPathUtil.isWindowsOs("Mac OS X"))
        assertFalse(FfmpegPathUtil.isWindowsOs("Linux"))
    }

    @Test
    fun `locateCommand picks where on windows and which otherwise`() {
        assertEquals("where", FfmpegPathUtil.locateCommand(true))
        assertEquals("which", FfmpegPathUtil.locateCommand(false))
    }

    @Test
    fun `candidatePaths returns unix paths`() {
        val paths = FfmpegPathUtil.candidatePaths("ffmpeg", false) { null }
        assertEquals(
            listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"),
            paths,
        )
    }

    @Test
    fun `candidatePaths windows with env expands all`() {
        val env =
            mapOf(
                "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
                "USERPROFILE" to "C:\\Users\\me",
                "ProgramFiles" to "C:\\Program Files",
            )
        val paths = FfmpegPathUtil.candidatePaths("ffmpeg", true) { env[it] }
        assertEquals(
            listOf(
                "C:\\Users\\me\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe",
                "C:\\Users\\me\\scoop\\shims\\ffmpeg.exe",
                "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
            ),
            paths,
        )
    }

    @Test
    fun `candidatePaths windows skips missing env and defaults ProgramFiles`() {
        val paths = FfmpegPathUtil.candidatePaths("ffprobe", true) { null }
        assertEquals(
            listOf(
                "C:\\ProgramData\\chocolatey\\bin\\ffprobe.exe",
                "C:\\ffmpeg\\bin\\ffprobe.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffprobe.exe",
            ),
            paths,
        )
    }
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests "com.github.audioplayer.FfmpegPathUtilTest"` — expect compile errors (functions undefined).

- [ ] **Step 3: Implement the pure functions** — add to the `FfmpegPathUtil` object (e.g. just below the `LOG` property; you will remove the old `FFMPEG_SEARCH_PATHS`/`FFPROBE_SEARCH_PATHS` in Task 2):

```kotlin
    fun isWindowsOs(osName: String): Boolean = osName.lowercase().contains("win")

    fun locateCommand(windows: Boolean): String = if (windows) "where" else "which"

    fun candidatePaths(
        command: String,
        windows: Boolean,
        env: (String) -> String?,
    ): List<String> =
        if (!windows) {
            listOf(
                "/opt/homebrew/bin/$command",
                "/usr/local/bin/$command",
                "/usr/bin/$command",
            )
        } else {
            val exe = "$command.exe"
            buildList {
                env("LOCALAPPDATA")?.let { add("$it\\Microsoft\\WinGet\\Links\\$exe") }
                env("USERPROFILE")?.let { add("$it\\scoop\\shims\\$exe") }
                add("C:\\ProgramData\\chocolatey\\bin\\$exe")
                add("C:\\ffmpeg\\bin\\$exe")
                add("${env("ProgramFiles") ?: "C:\\Program Files"}\\ffmpeg\\bin\\$exe")
            }
        }
```

- [ ] **Step 4: Run to verify pass** — `./gradlew test --tests "com.github.audioplayer.FfmpegPathUtilTest"` — expect PASS (existing + 6 new).

- [ ] **Step 5: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/FfmpegPathUtil.kt \
        src/test/kotlin/com/github/audioplayer/FfmpegPathUtilTest.kt
git commit -m "feat: ffmpeg検出のOS依存ロジックを純粋関数化(Windows対応の土台)"
```

---

## Task 2: autoDetect を OS 分岐に書き換え

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/FfmpegPathUtil.kt`

OS依存の実プロセス実行は macOS の既存テスト（`autoDetectFfmpeg`/`autoDetectFfprobe` が非null）で回帰確認する。

- [ ] **Step 1: Replace the search-path constants and autoDetect** — remove the `FFMPEG_SEARCH_PATHS` and `FFPROBE_SEARCH_PATHS` properties. Replace `autoDetectFfmpeg`/`autoDetectFfprobe` and the private `autoDetect(command, searchPaths)` with:

```kotlin
    fun autoDetectFfmpeg(): String? = autoDetect("ffmpeg")

    fun autoDetectFfprobe(): String? = autoDetect("ffprobe")

    private fun autoDetect(command: String): String? {
        val windows = isWindowsOs(System.getProperty("os.name").orEmpty())
        val locate = locateCommand(windows)

        // PATH から where/which で検索
        try {
            val process =
                ProcessBuilder(locate, command)
                    .redirectErrorStream(true)
                    .start()
            val result =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            if (process.waitFor() == 0 && result.isNotEmpty()) {
                LOG.info("Found $command via $locate: $result")
                return result
            }
        } catch (e: Exception) {
            LOG.info("'$locate $command' failed: ${e.message}")
        }

        // 既知のパスから検索
        for (path in candidatePaths(command, windows) { System.getenv(it) }) {
            if (File(path).exists()) {
                LOG.info("Found $command at known path: $path")
                return path
            }
        }

        LOG.warn("$command not found")
        return null
    }
```

Keep `findFfmpeg`, `findFfprobe`, `testExecutable` unchanged.

- [ ] **Step 2: Verify** — `./gradlew test --tests "com.github.audioplayer.FfmpegPathUtilTest"` (must still pass on macOS, incl. the existing `autoDetectFfmpeg returns path...` tests), then `./gradlew buildPlugin`.

- [ ] **Step 3: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/FfmpegPathUtil.kt
git commit -m "feat: ffmpeg/ffprobe自動検出をWindows(where+既知パス)対応に"
```

---

## Task 3: loadVisualization の request-token ガード

**Files:**
- Modify: `src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt`

`runIde` は実行しない。`./gradlew buildPlugin` + `./gradlew test` で確認。

- [ ] **Step 1: Add the request-id field** — near the other private fields (e.g. close to `private var positionTimer`), add:

```kotlin
    private var visualizationRequestId = 0
```

- [ ] **Step 2: Guard loadVisualization** — in `loadVisualization()`, capture an id at the top and bail in the completion callback if stale. The method becomes:

```kotlin
    private fun loadVisualization() {
        val requestId = ++visualizationRequestId
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
                if (requestId != visualizationRequestId) return@invokeLater
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

- [ ] **Step 3: Verify** — `./gradlew buildPlugin` (success) and `./gradlew test` (full suite green).

- [ ] **Step 4: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt
git commit -m "fix: loadVisualizationにrequest-tokenを導入し連打時の表示取り違えを解消"
```

---

## 完了条件
- `./gradlew test` 緑、`./gradlew buildPlugin` 成功、`./gradlew ktlintCheck` 違反なし。
- Windows の候補パス生成・コマンド選択が純粋関数テストで検証済み。macOS 既存検出テストは緑のまま。
