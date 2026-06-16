# SP6: IDE統合 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** プロジェクトツリー右クリックで Audio Player を開く＋音声ファイルのドラッグ&ドロップで開く。

**Architecture:** F1 は `AnAction`（ProjectViewPopupMenu）。F2 は `firstAudioFile` 純粋関数＋`Project` を provider→editor→panel に渡して `DropTarget` で開く。

**設計仕様:** `docs/superpowers/specs/2026-06-16-sp6-ide-integration-design.md`

**共通の注意:** コミット前 `./gradlew ktlintFormat`。`runIde` 実行しない（導線は人手確認）。`build-signed.sh`/`build.gradle.kts` に触れない。各タスク列挙ファイルのみ `git add`。各タスク後 `./gradlew test`+`buildPlugin` 緑。

---

## Task 1: 右クリックアクション

**Files:** New `OpenInAudioPlayerAction.kt`; Modify `src/main/resources/META-INF/plugin.xml`.

ユニットテストは不要（プラットフォーム依存。有効化判定は既存の `AudioConverter.isSupportedExtension` を使用）。`buildPlugin` でコンパイル確認。

- [ ] **Step 1: Create the action** — `src/main/kotlin/com/github/audioplayer/OpenInAudioPlayerAction.kt`:

```kotlin
package com.github.audioplayer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager

class OpenInAudioPlayerAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            file != null && !file.isDirectory && AudioConverter.isSupportedExtension(file.extension)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
```

- [ ] **Step 2: Register in plugin.xml** — add an `<actions>` block (sibling of `<extensions>`), inside `<idea-plugin>`:

```xml
    <actions>
        <action
            id="com.github.audioplayer.OpenInAudioPlayer"
            class="com.github.audioplayer.OpenInAudioPlayerAction"
            text="Audio Player で開く"
            description="選択した音声ファイルを Audio Player で開く">
            <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
        </action>
    </actions>
```

- [ ] **Step 3: Verify** — `./gradlew test` (full green), `./gradlew buildPlugin` (success).

- [ ] **Step 4: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/OpenInAudioPlayerAction.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: プロジェクトツリー右クリックの「Audio Playerで開く」アクションを追加"
```

---

## Task 2: ドラッグ&ドロップで開く

**Files:** Modify `AudioConverter.kt`, `AudioFileEditorProvider.kt`, `AudioFileEditor.kt`, `AudioPlayerPanel.kt`; Test `AudioConverterTest.kt`.

- [ ] **Step 1: Failing test for firstAudioFile** — add to `AudioConverterTest.kt`:

```kotlin
    @Test
    fun `firstAudioFile picks first supported`() {
        val files =
            listOf(
                java.io.File("/a/readme.txt"),
                java.io.File("/a/song.mp3"),
                java.io.File("/a/b.wav"),
            )
        assertEquals("song.mp3", AudioConverter.firstAudioFile(files)?.name)
    }

    @Test
    fun `firstAudioFile null when none supported`() {
        assertNull(AudioConverter.firstAudioFile(listOf(java.io.File("/a/x.txt"))))
    }
```

- [ ] **Step 2: Verify fail** — `./gradlew test --tests "com.github.audioplayer.AudioConverterTest"` → compile error.

- [ ] **Step 3: Implement firstAudioFile** — add to `AudioConverter.kt`:

```kotlin
    fun firstAudioFile(files: List<File>): File? = files.firstOrNull { isSupportedExtension(it.extension) }
```

- [ ] **Step 4: Verify pass** — `./gradlew test --tests "com.github.audioplayer.AudioConverterTest"` → PASS.

- [ ] **Step 5: Thread Project through** —
  - `AudioFileEditorProvider.kt`: change `createEditor` to `AudioFileEditor(file, project)`.
  - `AudioFileEditor.kt`: change the class signature to `class AudioFileEditor(private val file: VirtualFile, private val project: com.intellij.openapi.project.Project)` and the panel creation to `AudioPlayerPanel(file, project)`.
  - `AudioPlayerPanel.kt`: change the class signature to `class AudioPlayerPanel(private val file: VirtualFile, private val project: Project) : JPanel(BorderLayout())`. Add `import com.intellij.openapi.project.Project`.

- [ ] **Step 6: Add the DropTarget** — in `AudioPlayerPanel.kt` add imports:

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
```

At the end of `init {}` (after `loadFile()`), set up the drop target:

```kotlin
        DropTarget(
            this,
            object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    try {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val dropped =
                            event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
                        val audio = AudioConverter.firstAudioFile(dropped)
                        if (audio != null) {
                            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(audio)
                            if (vf != null) {
                                FileEditorManager.getInstance(project).openFile(vf, true)
                            }
                        }
                        event.dropComplete(true)
                    } catch (e: Exception) {
                        log.error("Drop failed", e)
                        event.dropComplete(false)
                    }
                }
            },
        )
```

(`log` field already exists on the panel from the SP2 fix. If not present, add `private val log = Logger.getInstance(AudioPlayerPanel::class.java)` and the `com.intellij.openapi.diagnostic.Logger` import.)

- [ ] **Step 7: Verify** — `./gradlew test` (full green), `./gradlew buildPlugin` (success). Confirm the provider compiles with the new `AudioFileEditor(file, project)` and panel signature.

- [ ] **Step 8: Format and commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/github/audioplayer/AudioConverter.kt \
        src/main/kotlin/com/github/audioplayer/AudioFileEditorProvider.kt \
        src/main/kotlin/com/github/audioplayer/AudioFileEditor.kt \
        src/main/kotlin/com/github/audioplayer/AudioPlayerPanel.kt \
        src/test/kotlin/com/github/audioplayer/AudioConverterTest.kt
git commit -m "feat: 音声ファイルのドラッグ&ドロップで開く対応を追加"
```

---

## 完了条件
- `./gradlew test` 緑、`./gradlew buildPlugin` 成功、`./gradlew ktlintCheck` 違反なし。
- `firstAudioFile` テスト済み。右クリック/D&Dの導線は人手（runIde）確認。
- 既存の `AudioFileEditorProviderTest` が `AudioFileEditor`/`AudioPlayerPanel` のコンストラクタ変更後も緑（必要なら provider 経由のテストを確認）。
