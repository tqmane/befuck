# BeFuck

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

BeRealの機能を拡張するXposedモジュールです。  
対応バージョン：**3.97.0 (3597523)**（`com.bereal.ft`）

## 主な機能

- **ぼかし解除**: タイムラインや投稿のモザイクを解除して閲覧できます。
- **投稿・メディア保存**: 写真、動画、BTS、RealMojiを高画質で端末に保存できます（撮影日時も保持）。
- **ギャラリー投稿**: 端末の写真や動画を選んでBeRealに投稿できます（切り抜き・位置情報・遅延投稿・公開範囲に対応）。
- **広告非表示**: タイムライン上のスポンサー投稿や広告を非表示にします。
- **保存リスト**: 過去に読み込んだ投稿の履歴一覧から、いつでもダウンロードできます。

## 使い方

1. モジュールをインストールし、LSPosed等のフレームワークで有効化して対象に **BeReal (`com.bereal.ft`)** を選択します。
2. BeRealを起動します。
3. ホーム画面のタイトル横にある **+** アイコンから、ギャラリー投稿や保存した投稿一覧、設定を開けます。各投稿のメニューからも保存できます。

保存先：端末の `Pictures/BeFuck` または `Movies/BeFuck`

## ビルド

JDK 17以上とAndroid SDKが必要です。

**Windows (PowerShell)**

```powershell
.\gradlew.bat :app:assembleDebug
```

**Linux / macOS**

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

生成APK：`app/build/outputs/apk/debug/app-debug.apk`

## ライセンス

Copyright 2026 **tqmane**。[PolyForm Noncommercial 1.0.0](LICENSE)。
