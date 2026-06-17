# SP4: 再生エンジン刷新 設計仕様

作成日: 2026-06-16
ベース: `feature/sp3-waveform-enhancements`（SP3）の上に積む。

## 背景と当初スコープ

SP4 の当初項目は3つ:
1. SourceDataLine ストリーミング再生（大ファイルのメモリ削減）
2. 音程維持の速度変更
3. VU・ピークメーター

## 重要な設計判断（自律実行のため明記）

**SourceDataLine へのストリーミング再生移行は、本サブプロジェクトでは見送る（将来の独立タスクとして人手の実機検証付きで実施を推奨）。** 理由:

- 現在の `javax.sound.sampled.Clip` ベースの再生は出荷済み（PR #1）で安定して動作している。これを SourceDataLine 上の自前再生ループ（バッファ書き込み・フレーム位置追跡・シーク/一時停止/ループの並行制御）に置き換えるのは**コア機能の大規模な書き換え**であり、クリックノイズ・位置ドリフト・デッドロック・ライン確保失敗といった不具合は**実際に音を鳴らして耳と目で確認しないと検出できない**。本自律実行ではサブエージェントもCIも音声を再生できないため、自動テスト＋ビルドが通っても実機で壊れるリスクが高い。
- ストリーミング化の主目的（数時間級ファイルのメモリ削減）は一般的な用途では限定的。
- ユーザーが求める2つの体感機能（**VU/ピークメーター**・**音程維持の速度変更**）は、コア再生を書き換えずに実現できる（下記）。

→ 本SP4では **VU/ピークメーター** と **音程維持の速度変更** を現行 Clip エンジン上で確実に実装する。ストリーミング移行はフォローアップ（人手の実機オーディオ検証込み）として残す。AudioPlayerService の公開APIは安定を保つため、将来ストリーミングへ差し替えてもUIは不変。

## ゴール

- **F1. VU/ピークメーター**: 再生中、現在位置付近のPCMからピーク/RMSを算出し、コントロール部にレベルメーターを表示。
- **F2. 音程維持の速度変更**: 0.5×〜2.0× の速度を選択。ffmpeg `atempo`（音程維持）で速度調整済みWAVを生成し、現在位置を保って差し替え再生。UIの時間軸は常に「元の長さ」基準。

## 非ゴール
- SourceDataLine ストリーミング再生（上記理由で見送り）。
- SP5（A-B）、SP6。

## F1. VU/ピークメーター

### レベル算出（純粋関数, `AudioPlayerService` companion）
- `computePeak(samples: ShortArray): Float` — `max(abs(s))/32768f`（0..1）。空配列→0。
- `computeRms(samples: ShortArray): Float` — `sqrt(mean(s^2))/32768f`（0..1）。空配列→0。

### サンプル取得
- `AudioPlayerService` は変換済みWAV（16-bit PCM, 既知フォーマット）を読める。再生中、現在の再生位置に対応するバイト位置から短い窓（例: 2048 frames）を読み、`ShortArray` に変換してレベルを得る `currentLevel(): Pair<Float,Float>?`（peak,rms。未ロード/取得不可は null）。
  - WAV のデータ開始オフセットとフレームサイズは `AudioInputStream`/`AudioFormat` から得る（PCM 16bit）。読み出しは小さく、100msタイマー周期で十分軽い。
- 速度変更中（rendered clip 再生時）は rendered WAV から同様に読む（位置は rendered 位置）。

### UI
- コントロール部に横長のレベルメーター（独自 `JComponent` `LevelMeter` か、簡易に `JProgressBar` 2本=L/R or peak/rms）。本仕様では**ピーク1本＋RMS下地**の簡易メーター（独自 `LevelMeterBar` 軽量コンポーネント、`level: Float`/`peak: Float` を持ち塗る）。
- 既存の100msタイマー（`startPositionTimer`）内で `playerService.currentLevel()` を取得しメーター更新。停止/一時停止時は 0 に減衰（簡易にゼロ表示）。

## F2. 音程維持の速度変更

### 速度調整WAVの生成
- `AudioConverter.buildAtempoCommand(ffmpeg, input, output, speed): List<String>` = `[ffmpeg, "-i", input, "-filter:a", "atempo=$speed", "-acodec","pcm_s16le","-ar","44100","-ac","2","-y", output]`（純粋・テスト可能）。speed は 0.5..2.0（単一 atempo の有効域）。
- `AudioConverter.renderAtempo(input, output, speed): Boolean` — 実行し exit 0。

### 位置・時間のマッピング（純粋関数, `AudioPlayerService` companion）
- 元の長さ `originalTotalMicros`（速度に依存しない、UI表示の総時間）。
- rendered clip 長 = `originalTotalMicros / speed`。
- `renderedToOriginalMicros(renderedMicros, speed): Long = (renderedMicros * speed).toLong()`
- `originalToRenderedMicros(originalMicros, speed): Long = (originalMicros / speed).toLong()`
- `currentMicroseconds`（UI, 元基準）= `renderedToOriginalMicros(clip.microsecondPosition, speed)`。
- `totalMicroseconds`（UI, 元基準）= `originalTotalMicros`。
- `seek(originalMicros)` → `clip.microsecondPosition = originalToRenderedMicros(originalMicros, speed)`。

### エンジン変更（API安定）
- `AudioPlayerService` に `originalTotalMicros: Long` を保持（speed=1.0 のロード時の clip 長）。
- `setSpeed(speed: Float)`:
  - 同一なら無視。1.0 は元WAVを使用（rendered不要）。
  - それ以外は ffmpeg で rendered WAV を生成（バックグラウンド）。現在の元位置 `pos` を保持。生成後、新 clip を開き、`microsecondPosition = originalToRenderedMicros(pos, speed)` にし、再生中なら resume。
  - 失敗時は速度1.0に戻し `onError`。
- `currentMicroseconds`/`totalMicroseconds`/`seek` は上記マッピングを用いる（speed=1.0 のとき恒等）。
- `setVolume`/`setLooping`/`pause`/`stop`/`play` は現行ロジックを維持（rendered clip に対して適用）。ループは rendered clip でそのまま。
- `dispose` は rendered の一時WAVも削除。

### UI
- コントロール部に速度選択（`JComboBox<String>` で `0.5x/0.75x/1.0x/1.25x/1.5x/2.0x`、既定 1.0x）。選択で `playerService.setSpeed(...)`。
- 速度を設定に記憶（`AudioPlayerSettings.lastSpeed: Float = 1.0f`）。次回ロード時に適用。
- 生成中は速度コンボを一時無効化し、完了で再有効化（`onStateChanged` or 専用コールバック）。簡易に setSpeed をバックグラウンド化し UI は楽観的更新。

## コンポーネント

| ファイル | 変更 |
|---|---|
| `AudioPlayerService.kt` | `computePeak`/`computeRms`/`renderedToOriginalMicros`/`originalToRenderedMicros`（純粋, companion）、`currentLevel()`、`setSpeed()`、`originalTotalMicros`、`currentMicroseconds`/`totalMicroseconds`/`seek` のマッピング対応、rendered一時WAV管理 |
| `AudioConverter.kt` | `buildAtempoCommand`/`renderAtempo` |
| `AudioPlayerSettings.kt` | `lastSpeed: Float = 1.0f` |
| `LevelMeterBar.kt` | 新規。軽量メーター（peak/rms を 0..1 で受け塗る） |
| `AudioPlayerPanel.kt` | 速度コンボ＋レベルメーター配置、タイマーでレベル更新、設定の速度復元/保存 |
| `AudioPlayerServiceTest.kt` | `computePeak`/`computeRms`/マッピング関数のテスト |
| `AudioConverterTest.kt` | `buildAtempoCommand` テスト |
| `AudioPlayerSettingsTest.kt` | `lastSpeed` 既定値テスト |

## テスト方針
- 純粋関数（computePeak/computeRms/位置マッピング/atempoコマンド）をユニットテスト。
- **実機オーディオ検証は別途人手が必要**（速度変更の音質・位置整合、メーターの追従）。spec/PRに明記し、`runIde` 確認を推奨。既存テストは緑維持。

## エッジケース
- 未ロードで currentLevel/setSpeed → 安全に no-op/null。
- speed=1.0 → rendered 不使用、マッピングは恒等。
- atempo 生成失敗/ffmpeg無し → 速度1.0へフォールバック＋通知。
- 速度変更の連打 → 最新のみ反映（リクエストID or 単純に最後の設定を採用）。
- ループ＋速度 → rendered clip でループ（音程維持済み）。
- メーター取得失敗 → 0 表示。

## リスクと検証メモ
- 本SPはコア再生の**内部マッピング変更**を含む（speed!=1 のとき位置/総時間が rendered と original で異なる）。speed=1.0 経路は現行と等価であることをテスト（マッピング恒等）。
- speed!=1 の正しさ（位置・シーク・ループ・音程）は実機検証必須。
