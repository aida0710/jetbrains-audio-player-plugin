# SP5: A-Bループ 設計仕様

作成日: 2026-06-16
ベース: `feature/sp4-playback-engine`（SP4）の上に積む。SP3（波形窓）と SP4（速度/位置マッピング）に依存。

## 背景
波形上で区間 [A,B] を指定し、その区間だけ繰り返し再生する（耳コピ/サンプル確認向け）。

## ゴール
- **A/B点の指定とクリア**: 現在の再生位置を A・B として記録。クリアで解除。
- **区間リピート**: A-B を有効化すると、再生位置が B 以上になったとき A へ戻る。
- **可視化**: 波形/スペクトラム上に A・B のマーカー線と区間の半透明シェードを表示（表示窓内のみ）。

## 非ゴール
- SP6（IDE統合）。マーカーの永続化（セッション内のみ）。波形ドラッグでの範囲選択（ボタン方式とする）。

## 方針

### ループ判定（純粋関数, `AudioPlayerService` companion）
- `shouldLoopBack(current: Long, a: Long, b: Long): Boolean = a >= 0 && b > a && current >= b`
- A/B/current はすべて**元タイムライン基準**のマイクロ秒。速度変更時も `currentMicroseconds`/`seek` が元基準でマッピングされるため、この判定は速度に依らず正しい。

### ループ実行
- `AudioPlayerPanel` の100msタイマー内で、A-B有効かつ `shouldLoopBack(currentMicroseconds, loopA, loopB)` なら `playerService.seek(loopA)`。
- ポーリング方式のため Clip/速度の内部実装に依存しない。粒度100msは実用上十分。

### マーカー表示（`TimelineImagePanel`）
- プロパティ `aMarkerMicros: Long = -1`、`bMarkerMicros: Long = -1`（setで repaint）。
- `paintComponent` で、A/B が表示窓 [viewStart,viewEnd] 内なら縦マーカー線、A<Bなら区間を半透明シェード（窓にクランプ）。

### UI（解析パネルのボタン行）
- `[A]`（A=現在位置）/`[B]`（B=現在位置）/`[A-B]` トグル（区間リピート有効）/`[ABクリア]`。
- A/B設定時、`loopA`/`loopB` を更新し `timelinePanel` のマーカーへ反映。B<=A の不正設定は無視（または B のみ設定）。
- A-B 有効化時に A・B 未設定なら無効のまま（両方必要）。

## コンポーネント
| ファイル | 変更 |
|---|---|
| `AudioPlayerService.kt` | `shouldLoopBack`（純粋, companion） |
| `TimelineImagePanel.kt` | `aMarkerMicros`/`bMarkerMicros` プロパティ＋マーカー/シェード描画 |
| `AudioPlayerPanel.kt` | A/B/トグル/クリアのボタン、タイマーでのループ実行、マーカー反映 |
| `AudioPlayerServiceTest.kt` | `shouldLoopBack` テスト |

## テスト方針
- `shouldLoopBack` をユニットテスト。描画・UIは結線として手動確認（runIde 任意）。既存テスト緑維持。

## エッジケース
- A未設定/B未設定 → ループしない、シェード無し（片方のマーカーのみ）。
- B<=A → 無視。
- A/B が表示窓外 → そのマーカーは描かない、シェードは窓にクランプ。
- 速度変更中 → 元基準で判定するため整合。
- ループ無効化/クリア → ポーリング停止、マーカー消去。
