# SP3: 波形表示の強化 設計仕様

作成日: 2026-06-16
ベース: `feature/sp2-ffmpeg-utilities`（SP2）の上に積む。

## 背景

波形/スペクトラム表示を「全体を一枚画像で見るだけ」から「拡大して細部を見られる」よう強化する。中核は `TimelineImagePanel` の座標系を **表示窓 `[viewStartMicros, viewEndMicros]`** 基準に作り替えること。ズーム時はその区間を ffmpeg で再生成して真の細部を出す（ピクセル拡大ではない）。

## ゴール（3機能）

- **時間ルーラー＋再生済み塗り分け**: 画像下部に時間目盛り（自動間隔・ラベル）。再生済み区間を半透明で塗る。
- **ステレオ L/R 別波形**: 波形を L/R 分離表示（`showwavespic` の `split_channels`）。設定で記憶。
- **ズーム/スクロール**: 表示窓を拡大/縮小・横スクロール。窓変更時はその区間を ffmpeg で再生成。

## 非ゴール
- SP4（エンジン刷新・VUメーター・速度変更）、SP5（A-B）、SP6。
- 縦ズーム、マウスホイール/ピンチ操作（ボタン＋スクロールバーで実装。ホイールは将来）。

## 表示窓モデルと座標変換（純粋関数）

`TimelineImagePanel` は表示窓 `viewStartMicros`/`viewEndMicros` を保持（既定 0..duration）。x↔時間は窓基準:

- `timeAtX(x, width, viewStart, viewEnd): Long` = `viewStart + (clampedX/width)*(viewEnd-viewStart)`（width<=0 や window 幅<=0 は viewStart を返す）。
- `xAtTime(t, width, viewStart, viewEnd): Int` = `((t-viewStart)*width/(viewEnd-viewStart))`（クランプ無し。窓外は負/幅超になる→呼び出し側で可視判定）。

ズーム計算も純粋関数:
- `zoomWindow(viewStart, viewEnd, factor, anchorFraction, duration): Pair<Long,Long>` — `factor`<1 で拡大（窓が狭く）、>1 で縮小。`anchorFraction`(0..1) の時間位置を保つように新窓を計算し、[0,duration] にクランプ。最小窓幅は例えば 100_000μs（0.1秒）。
- `panWindow(viewStart, viewEnd, deltaMicros, duration): Pair<Long,Long>` — 窓幅を保って平行移動、[0,duration] クランプ。

ルーラー目盛り（純粋）:
- `computeTicks(viewStart, viewEnd, width, targetSpacingPx): List<Tick>` （`Tick(xPixel: Int, label: String)`）。
  - 窓幅と `width/targetSpacingPx` から「きれい」な間隔（1/2/5×10^n 秒）を選ぶ。
  - 窓内の間隔倍数ごとに `xAtTime` で x を求め、ラベルは時間（`mm:ss`、間隔が1秒未満なら `mm:ss.f`）。
  - 端の半端や width<=0/窓幅<=0 は空リスト。

これらは UI/OS 非依存でユニットテストする。

## 画像のセグメント生成（ffmpeg）

`AudioAnalyzer` の生成を区間対応に拡張（既存呼び出しを壊さないようデフォルト引数）:
- `buildWaveformCommand(ffmpeg, input, output, width, height, startSec: Double? = null, lenSec: Double? = null, splitChannels: Boolean = false): List<String>`
  - `startSec`/`lenSec` が非 null なら `-ss <start>` と `-t <len>` を **`-i` の前**に挿入（高速シーク）。
  - `splitChannels` 時は filter を `showwavespic=s=WxH:split_channels=1:colors=0x4488CC`。
- `buildSpectrumCommand(...)` も `startSec`/`lenSec` を追加（spectrum に split_channels は無し）。
- `generateWaveformImage`/`generateSpectrumImage` に同じ任意引数を追加し `generateImage` へ委譲。`generateImage` も `startSec`/`lenSec`/`filterExtra` を受け取れるよう調整。
- 全体表示時は `startSec=0, lenSec=duration`（または null）。窓表示時は `startSec=viewStart秒, lenSec=(viewEnd-viewStart)秒`。

## TimelineImagePanel の描画拡張

`paintComponent`:
1. 画像（窓に対応した1枚）を全幅に描画。
2. **再生済み塗り**: `currentPosition` が窓内なら、x=0 から `xAtTime(min(pos,viewEnd))` までを半透明オーバーレイ。
3. **再生位置ライン**: `positionMicros` が窓内（viewStart..viewEnd）なら `xAtTime` の位置に縦線。窓外は描かない。
4. **ルーラー**: 下部に目盛り線＋ラベル（`computeTicks`）。
- クリック/ドラッグ: `timeAtX(x, width, viewStart, viewEnd)` を `onSeek`。

新プロパティ: `viewStartMicros`/`viewEndMicros`（setで repaint）。`durationMicros` は維持（窓初期化や全体表示の基準）。

## 設定

`AudioPlayerSettings.SettingsState` に `waveformSplitChannels: Boolean = false` を追加（L/R 分離の記憶）。

## UI（解析パネル）

解析パネルのボタン行に追加:
- `[− 縮小]`（zoom out=窓を広げる）/`[＋ 拡大]`（zoom in）/`[全体]`（fit=窓を0..durationに）
- `[L/R分離]` トグル（`JToggleButton`、`waveformSplitChannels` を反映/保存、切替で再生成）
- 画像下に横スクロール用 `JScrollBar`（HORIZONTAL）。窓が全体未満のとき有効。値=viewStart 相当、エクステント=窓幅。

窓変更（zoom/pan/分離切替）時:
- `viewStart/viewEnd` を更新 → `timelinePanel` に反映（ライン/ルーラー即時更新）→ その区間を `loadVisualization()` で再生成。
- 生成は SP1 の `visualizationRequestId` トークンで最新のみ反映。
- スクロールバーの範囲・値も窓に同期。

ズームは現在の窓中心（または再生位置が窓内ならそれ）をアンカーに。ステップは factor=0.5（拡大）/2.0（縮小）。

## コンポーネント

| ファイル | 変更 |
|---|---|
| `TimelineImagePanel.kt` | 窓プロパティ追加、`timeAtX`/`xAtTime` を窓基準に変更、`zoomWindow`/`panWindow`/`computeTicks`（純粋）追加、再生済み塗り・ルーラー描画、窓外ラインの非表示 |
| `AudioAnalyzer.kt` | `buildWaveformCommand`/`buildSpectrumCommand`/`generate*Image`/`generateImage` に `startSec`/`lenSec`/`splitChannels` 追加 |
| `AudioPlayerSettings.kt` | `waveformSplitChannels: Boolean = false` |
| `AudioPlayerPanel.kt` | 窓状態(`viewStartMicros`/`viewEndMicros`)、ズーム/全体/分離トグル/横スクロールバー、`loadVisualization` を窓区間で生成するよう拡張、窓をパネルへ反映 |
| `TimelineImagePanelTest.kt` | 窓基準 `timeAtX`/`xAtTime`、`zoomWindow`、`panWindow`、`computeTicks` のテスト |
| `AudioAnalyzerTest.kt` | セグメント/`splitChannels` 付きコマンドのテスト |
| `AudioPlayerSettingsTest.kt` | `waveformSplitChannels` 既定値テスト |

## テスト方針
- 窓座標変換・ズーム・パン・ルーラー目盛り・コマンド組み立てを純粋関数でユニットテスト。
- 描画・ffmpeg実行・スクロールバー連動は UI 結線として手動確認（runIde 任意）。既存テスト緑維持。

## エッジケース
- duration=0 / 画像未生成 → ライン・塗り・ルーラー描かず、クリック無効。
- 窓幅が最小未満になるズーム → 最小幅でクランプ。
- 窓が [0,duration] 全体 → スクロールバー無効、`-ss/-t` 省略（全体生成）。
- 再生位置が窓外 → ラインを描かない（塗りは窓内部分のみ）。
- 連打ズーム/パン → request-token で最新区間のみ反映。
- 既存の `buildWaveformCommand` 呼び出し（引数5個）→ デフォルト引数で不変。

## 互換性メモ
- `timeAtX`/`xAtTime` の**シグネチャ変更**（duration → viewStart/viewEnd）。呼び出し元（`TimelineImagePanel` 内と `AudioPlayerPanel` の `seekToMicros` のスライダー計算は別途）を更新。`AudioPlayerPanel` 側はスライダー値計算に `durationMicros` を使い続ける（スライダーは全体基準のまま）。
