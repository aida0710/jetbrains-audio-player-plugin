# SP6: IDE統合 設計仕様

作成日: 2026-06-16
ベース: `feature/sp5-ab-loop`（SP5）の上に積む。

## 背景
IDEとの統合で開く操作を便利にする。

## ゴール
- **F1. プロジェクトツリーの右クリックから開く**: 音声ファイルを選んで右クリック→「Audio Player で開く」で再生エディタ（波形は自動生成）を開く。
- **F2. ドラッグ&ドロップで開く**: 音声ファイルを Audio Player パネルにドロップすると、その音声を新しいエディタタブで開く。

## 非ゴール
- 既に二重クリックでは開ける（FileType/FileEditorProvider 登録済み）。本SPは右クリックとD&Dの導線追加。

## F1. 右クリックアクション
- `OpenInAudioPlayerAction : AnAction` を新規作成。
  - `update`: 単一の音声ファイル（`CommonDataKeys.VIRTUAL_FILE` が非ディレクトリかつ `AudioConverter.isSupportedExtension(ext)`）のときのみ `isEnabledAndVisible`。`getActionUpdateThread() = BGT`。
  - `actionPerformed`: `FileEditorManager.getInstance(project).openFile(file, true)`（登録済みの AudioFileEditorProvider により Audio Player で開く）。
- `plugin.xml` の `<actions>` に登録し、`ProjectViewPopupMenu` グループへ追加。テキスト「Audio Player で開く」。

## F2. ドラッグ&ドロップ
- `AudioConverter.firstAudioFile(files: List<File>): File?` を追加（拡張子が対応のものの先頭。純粋・テスト可能）。
- `Project` を `AudioFileEditorProvider.createEditor` → `AudioFileEditor` → `AudioPlayerPanel` に受け渡す（コンストラクタ引数追加）。
- `AudioPlayerPanel` に `DropTarget` を設定。`javaFileListFlavor` のドロップを受け、`firstAudioFile` で音声を選び、`LocalFileSystem.refreshAndFindFileByIoFile` で VirtualFile 化し、`FileEditorManager.getInstance(project).openFile(vf, true)` で開く。例外時は `dropComplete(false)`。

## コンポーネント
| ファイル | 変更 |
|---|---|
| `OpenInAudioPlayerAction.kt` | 新規。右クリックアクション |
| `plugin.xml` | `<actions>` に登録（ProjectViewPopupMenu） |
| `AudioConverter.kt` | `firstAudioFile` 追加 |
| `AudioFileEditorProvider.kt` | `createEditor` で `AudioFileEditor(file, project)` |
| `AudioFileEditor.kt` | コンストラクタに `project` 追加し `AudioPlayerPanel(file, project)` |
| `AudioPlayerPanel.kt` | コンストラクタに `project` 追加、`DropTarget` で音声ドロップを開く |
| `AudioConverterTest.kt` | `firstAudioFile` テスト |

## テスト方針
- `firstAudioFile` をユニットテスト。アクションの導線・D&D・ファイル選択ダイアログ等は実機（runIde）で人手確認。既存テスト緑維持。

## エッジケース
- 非音声ファイル選択 → アクション非表示。
- 複数ファイルドロップ → 先頭の音声を開く。音声が無ければ何もしない。
- ドロップ対象が一時的に VFS 未認識 → `refreshAndFindFileByIoFile` で更新検索。見つからなければ無視。
- ディレクトリ選択 → アクション無効。

## 互換性メモ
- `AudioFileEditor`/`AudioPlayerPanel` のコンストラクタにそれぞれ `project: Project` を追加（呼び出し元は provider のみ）。
