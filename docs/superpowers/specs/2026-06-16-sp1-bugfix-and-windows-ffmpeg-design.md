# SP1: バグ予防・小改善 設計仕様

作成日: 2026-06-16
親計画: Audio Player 機能拡張の分解（SP1〜SP6）の最初のサブプロジェクト。
ベース: `feature/usability-improvements`（PR #1）の上に積む（`loadVisualization` 等に依存するため）。

## 背景

使いやすさ向上（PR #1）の後、レビューと検討で2つの改善点が判明した。

1. `FfmpegPathUtil` の自動検出は *nix 前提（`which` コマンド＋ `/opt/homebrew/bin` 等のパス）で、Windows では ffmpeg/ffprobe が検出されない。Windows は `which` が無く（`where` が必要）、実行ファイル名は `.exe`、一般的なインストール先も異なる。
2. `AudioPlayerPanel.loadVisualization()` は呼び出しごとにバックグラウンドで画像生成し、完了時に `timelinePanel` を更新する。波形/スペクトラムを素早く切り替えると、遅れて完了した古いジョブが新しい表示を上書きしうる（last-writer の取り違え）。

## ゴール

- **(a) Windows の ffmpeg/ffprobe 自動検出**: Windows でも PATH 経由（`where`）＋一般的なインストール先のフォールバックで検出できる。macOS/Linux の既存挙動は不変。
- **(b) loadVisualization の競合解消**: 最新の生成リクエストの結果だけが `timelinePanel` に反映される。

## 非ゴール

- SP2〜SP6 の項目（ユーティリティ、波形強化、エンジン刷新、A-Bループ、IDE統合）。

## (a) Windows の ffmpeg/ffprobe 自動検出

### 方針

`FfmpegPathUtil` に OS 依存部分を切り出し、純粋関数化してテスト可能にする。

純粋関数（companion/トップレベルの `object` 内）:
- `isWindowsOs(osName: String): Boolean = osName.lowercase().contains("win")`
- `locateCommand(windows: Boolean): String = if (windows) "where" else "which"`
- `candidatePaths(command: String, windows: Boolean, env: (String) -> String?): List<String>` — `command` は `"ffmpeg"` または `"ffprobe"`。OS に応じた既知パスのフォールバック一覧を返す。環境変数に依存する候補は `env` が `null` を返したらスキップ。

`candidatePaths` の中身:
- **非 Windows**（既存維持）: `["/opt/homebrew/bin/$command", "/usr/local/bin/$command", "/usr/bin/$command"]`
- **Windows**（実行ファイルは `$command.exe`）。順に:
  1. `env("LOCALAPPDATA")` があれば `"$LOCALAPPDATA\\Microsoft\\WinGet\\Links\\$command.exe"`（winget）
  2. `env("USERPROFILE")` があれば `"$USERPROFILE\\scoop\\shims\\$command.exe"`（scoop）
  3. `"C:\\ProgramData\\chocolatey\\bin\\$command.exe"`（chocolatey, 固定）
  4. `"C:\\ffmpeg\\bin\\$command.exe"`（手動展開, 固定）
  5. `"${env("ProgramFiles") ?: "C:\\Program Files"}\\ffmpeg\\bin\\$command.exe"`（手動展開）

### autoDetect の改修

現行の `autoDetect(command, searchPaths)` を `autoDetect(command)` に変更:
1. `windows = isWindowsOs(System.getProperty("os.name").orEmpty())`
2. `locate = locateCommand(windows)`
3. `ProcessBuilder(locate, command)` を実行し、出力の**先頭行**を trim して採用（`where` は複数行を返しうる）。exit 0 かつ非空なら返す。
4. 失敗時は `candidatePaths(command, windows) { System.getenv(it) }` を走査し、存在するファイルを返す。
5. 見つからなければ `null`。

`autoDetectFfmpeg()` / `autoDetectFfprobe()` は `autoDetect("ffmpeg")` / `autoDetect("ffprobe")` に。既存の `FFMPEG_SEARCH_PATHS`/`FFPROBE_SEARCH_PATHS` 定数は `candidatePaths` に置き換えて削除。`findFfmpeg`/`findFfprobe`/`testExecutable` は変更なし。

## (b) loadVisualization の競合解消（request-token）

`AudioPlayerPanel` に `private var visualizationRequestId = 0` を追加。`loadVisualization()` の先頭で `val requestId = ++visualizationRequestId` をキャプチャ。バックグラウンド生成完了後の `SwingUtilities.invokeLater {}` 冒頭で `if (requestId != visualizationRequestId) return@invokeLater` とし、最新リクエストのみ `timelinePanel` を更新する。`loadVisualization` はボタン/`loadFile`/トグルから EDT で呼ばれ、`visualizationRequestId` の読み書きも EDT 上のみ → 同期不要。

## コンポーネント

| ファイル | 変更 |
|---|---|
| `FfmpegPathUtil.kt` | `isWindowsOs`/`locateCommand`/`candidatePaths` を純粋関数として追加。`autoDetect` を OS 分岐＋先頭行採用＋`candidatePaths` フォールバックに改修。旧検索パス定数を削除 |
| `FfmpegPathUtilTest.kt` | `isWindowsOs`/`locateCommand`/`candidatePaths`（*nix と Windows、env 欠如時のスキップ）の純粋関数テストを追加。既存の macOS 検出テストは維持 |
| `AudioPlayerPanel.kt` | `visualizationRequestId` 追加、`loadVisualization` に token ガード |

## テスト方針

- 純粋関数をユニットテスト（UI/OS 非依存。`candidatePaths` は `env` ラムダを注入して Windows ケースも macOS 上で検証）。
- 実プロセス実行（`where`/`which`）は OS 依存のため統合的に扱い、既存の `autoDetectFfmpeg`/`autoDetectFfprobe` テスト（実マシンで非 null）を維持。
- race fix は EDT 上ロジックのため `runIde` の連打目視は任意。

## エッジケース

- `where`/`which` が無い・失敗 → 既知パスフォールバック。
- 環境変数未設定 → その候補をスキップ（`ProgramFiles` は既定 `C:\Program Files` に）。
- `where` の複数行出力 → 先頭行を採用。
- 生成リクエストの取り違え → token 不一致で破棄。
