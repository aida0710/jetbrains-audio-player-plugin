# SP2: ffmpeg活用ユーティリティ 設計仕様

作成日: 2026-06-16
ベース: `feature/sp1-bugfix-and-windows-ffmpeg`（SP1）の上に積む。

## 背景

ffmpeg/ffprobe を活用した実用ユーティリティを4つ追加する。いずれも「テスト可能な純粋コア（コマンド組み立て・出力解析・整形）＋薄いUI結線」で構成する。

## ゴール（4機能）

- **F1. タグ表示＋メタデータのコピー**: ID3等のフォーマットタグ（Title/Artist/Album/Year/Genre/Track）を情報テーブルに表示し、テーブル内容をクリップボードへコピーできる。
- **F2. ラウドネス測定（LUFS）**: ffmpeg `ebur128` で統合ラウドネスを測定して表示する（オンデマンド）。
- **F3. 別フォーマットで書き出し**: ffmpeg で別形式（拡張子から推定）へ変換して保存する。
- **F4. 画像エクスポート**: 現在表示中の波形/スペクトラム画像を PNG 保存する。

## 非ゴール
- SP3〜SP6 の項目。

## F1. タグ表示＋コピー

### データ
- `AudioMetadata` に `tags: Map<String, String> = emptyMap()` を追加。
- `AudioProbe` に純粋関数 `parseTags(json: String): Map<String, String>` を追加。最初の `"tags": { ... }` オブジェクト内の `"key": "value"`（文字列値）を抽出し、キーを小文字化したマップを返す（タグが無ければ空マップ）。`parseJson` で `tags = parseTags(json)` を設定。
- ネストの無いフラットなタグ前提（`{` を含まない範囲を1ブロックとして取る）。値内のエスケープは簡易扱い。

### 表示
- `AudioPlayerPanel.updateInfoTable` の既存行の後に、既知タグが存在すれば順に行追加。キー(小文字)→ラベル: `title→Title`, `artist→Artist`, `album→Album`, `date→Year`, `genre→Genre`, `track→Track`。

### コピー
- 情報パネルに `[情報をコピー]` ボタン。クリックで情報テーブル全行をテキスト化しクリップボードへ。
- 純粋関数（`AudioPlayerPanel` companion）`infoRowsToText(rows: List<Pair<String, String>>): String = rows.joinToString("\n") { "${it.first}\t${it.second}" }`。
- クリップボード: `CopyPasteManager.getInstance().setContents(StringSelection(text))`。

## F2. ラウドネス測定（LUFS）

### 解析（新規 `LoudnessAnalyzer` object）
- 純粋 `buildEbur128Command(ffmpeg, input): List<String>` = `[ffmpeg, "-hide_banner", "-i", input, "-filter_complex", "ebur128", "-f", "null", "-"]`。
- 純粋 `parseIntegratedLufs(output: String): String?` — `I:\s*(-?\d+(?:\.\d+)?)\s*LUFS` に**最後にマッチ**した数値を使い `"<n> LUFS"` を返す（要約ブロックの統合値が末尾に出るため）。無ければ null。
- `measure(file): String?` — コマンド実行（`redirectErrorStream(true)` で stderr 取り込み）→ `parseIntegratedLufs`。

### UI
- 情報パネルに `[ラウドネス測定]` ボタン。クリックでボタン無効化→バックグラウンド測定→完了時に情報テーブルの「Loudness」行を追加/更新（既存 Loudness 行があれば置換）→ボタン再有効化。ffmpeg 無ければ "ffmpeg required"。

## F3. 別フォーマットで書き出し

### 変換（`AudioConverter` 拡張）
- 純粋 `buildExportCommand(ffmpeg, input, output): List<String>` = `[ffmpeg, "-i", input, "-y", output]`（出力拡張子から ffmpeg がフォーマット推定）。
- `export(input: File, output: File): Boolean` — 実行し exit 0 を返す。

### UI
- 情報パネルに `[別形式で書き出し]` ボタン → `JFileChooser`（保存）で出力先＋拡張子を選択 → バックグラウンドで `export` → 成否をバルーン通知（通知グループ "Audio Player" を再利用）。

## F4. 画像エクスポート

### UI
- 解析パネルのボタン行に `[画像を保存]` を追加。`timelinePanel.image != null` のとき `JFileChooser`（保存、既定名は下記）で PNG 保存（`ImageIO.write(image, "png", file)`）。画像が無ければ通知して no-op。
- 純粋 `defaultImageFileName(baseName: String, view: String): String` = `"${baseName}_${view}.png"`（view は "waveform"/"spectrum"）。`AudioPlayerPanel` companion に置きテスト。
- 現在の表示種別は `settingsState.defaultView` を使う。`baseName` は `file.nameWithoutExtension`。

## コンポーネント

| ファイル | 変更 |
|---|---|
| `AudioProbe.kt` | `AudioMetadata.tags` 追加、`parseTags` 追加、`parseJson` で tags 設定 |
| `LoudnessAnalyzer.kt` | 新規。`buildEbur128Command` / `parseIntegratedLufs` / `measure` |
| `AudioConverter.kt` | `buildExportCommand` / `export` 追加 |
| `AudioPlayerPanel.kt` | 情報パネルにボタン行（コピー/LUFS/書き出し）、解析行に画像保存、タグ行表示、`infoRowsToText`・`defaultImageFileName`（companion, 純粋） |
| `AudioProbeTest.kt` | `parseTags` テスト |
| `LoudnessAnalyzerTest.kt` | 新規。`buildEbur128Command` / `parseIntegratedLufs` テスト |
| `AudioConverterTest.kt` | `buildExportCommand` テスト追加 |
| `AudioPlayerPanelInfoTest.kt` | 新規。`infoRowsToText` / `defaultImageFileName` テスト |

## テスト方針
- 純粋関数（parseTags / parseIntegratedLufs / buildEbur128Command / buildExportCommand / infoRowsToText / defaultImageFileName）をユニットテスト。
- ffmpeg実行・ファイル選択・クリップボード・画像保存はOS/環境依存のためUI結線として手動確認対象（runIdeは任意）。既存テストは緑維持。

## エッジケース
- タグ無し → 空マップ、行追加なし。
- LUFS 解析失敗（パターン無し）→ "Loudness: 測定不可" 表示。
- 書き出し失敗/ffmpeg無し → 失敗通知。
- 画像未生成で画像保存 → 通知して no-op。
- 保存ダイアログでキャンセル → 何もしない。

## UI配置（情報パネル下部にボタン行）
- 情報テーブルの下に `[情報をコピー] [ラウドネス測定] [別形式で書き出し]` の `FlowLayout(LEFT)` 行を追加。
- 解析パネルのボタン行（既存 Waveform/Spectrum の隣）に `[画像を保存]` を追加。
