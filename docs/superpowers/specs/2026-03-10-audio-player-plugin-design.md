# JetBrains Audio Player Plugin — Design Spec

## Overview

JetBrains IDEで音声ファイルをエディタとして開くと、再生コントロール付きのカスタムエディタUIを表示するプラグイン。JAVE2（FFmpegのJavaラッパー）で幅広いフォーマットをwavに変換し、Java Sound APIで再生する。

## Supported Formats

mp3, wav, ogg, flac, aac, m4a, wma など（JAVE2/ffmpegがサポートする全フォーマット）

## Architecture

### Audio Pipeline

```
音声ファイル → JAVE2(ffmpeg)でwav/pcm変換 → Java Sound API で再生
```

### Components

1. **AudioFileEditorProvider** — `FileEditorProvider`を実装。音声ファイル拡張子を検出しカスタムエディタを返す。
2. **AudioFileEditor** — `FileEditor`を実装。再生UIを持つカスタムエディタ。
3. **AudioPlayerService** — 変換・再生のロジック。JAVE2でwav変換→`Clip`/`SourceDataLine`で再生制御。シーク・音量・ループの状態管理。
4. **AudioPlayerPanel** — Swing UIパネル。全コントロールの描画とイベント処理。

### UI Layout

```
┌─────────────────────────────────────────┐
│  🎵 filename.mp3          00:32 / 03:45 │
│                                         │
│  [▶/⏸] [⏹]  ───●─────────────  🔁     │
│                                         │
│  🔊 ────●──────────                     │
└─────────────────────────────────────────┘
```

- 再生/一時停止トグルボタン、停止ボタン
- シークバー（ドラッグ操作対応、現在位置/総時間表示）
- 音量スライダー
- ループ再生トグル
- ファイル名表示

## Build System

- Gradle (Kotlin DSL) + IntelliJ Platform Gradle Plugin
- 現在の`.iml`ベースからGradleベースに移行

## Dependencies

- `ws.schild:jave-all-deps` — JAVE2 + 全プラットフォームのffmpegバイナリ同梱
- IntelliJ Platform SDK

## Error Handling

- 変換失敗時: エディタ上にエラーメッセージを表示
- 未対応フォーマット: 通知バーで警告
- ファイル読み込み失敗: エディタ上にエラーメッセージを表示
