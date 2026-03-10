# Audio Player - JetBrains IDE プラグイン

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/30608-audio-player.svg)](https://plugins.jetbrains.com/plugin/30608-audio-player)

JetBrains IDE 上で音声ファイルを直接再生できるプラグインです。

## 機能

- 再生 / 一時停止 / 停止 / シーク / 音量調整
- ループ再生
- キーボードショートカット（Space で再生/一時停止）
- ファイルメタデータ表示（エンコーディング、フォーマット、チャンネル数、サンプルレート、再生時間）
- 波形・スペクトラム表示（ファイルを開くとスペクトラムを自動生成）

## 対応フォーマット

mp3, wav, ogg, flac, aac, m4a, wma, opus, ape, aiff

## UI レイアウト

```
┌──────────────────┬──────────────────┐
│ ファイル情報       │ 再生コントロール    │
├──────────────────┴──────────────────┤
│ [Waveform] [Spectrum]               │
│ 波形 / スペクトラム画像                │
└─────────────────────────────────────┘
```

## 必要環境

- **JetBrains IDE** 2024.3 以降
- **ffmpeg** — 音声再生および波形/スペクトラム画像生成に必要
- **ffprobe** — メタデータ表示に必要

### ffmpeg のインストール

```bash
# macOS
brew install ffmpeg

# Ubuntu / Debian
sudo apt install ffmpeg

# Windows
winget install ffmpeg
```

## ビルド

```bash
./gradlew buildPlugin
```

`build/distributions/` に ZIP ファイルが生成されます。

## テスト

```bash
./gradlew test
```

## リント

```bash
# チェック
./gradlew ktlintCheck

# 自動フォーマット
./gradlew ktlintFormat
```

## 開発用 IDE 起動

```bash
./gradlew runIde
```

起動した IDE で音声ファイルを開くとプラグインが動作します。

## ライセンス

[MIT License](LICENSE)
