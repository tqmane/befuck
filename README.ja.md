# BeFuck

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

BeRealの機能を拡張するXposedモジュールです。  
**LSPosed** および **NPatch** に対応しています。  
対応バージョン：**3.97.0 (3597523)** / **3.97.1 (3599414)**（`com.bereal.ft`）

## スクリーンショット

| ぼかし解除 & 保存 | ギャラリー投稿 | 位置情報の選択 |
| :---: | :---: | :---: |
| <img src="artwork/screenshots/01_unblur_download.png" width="220" alt="ぼかし解除と保存" /> | <img src="artwork/screenshots/02_gallery_post.png" width="220" alt="ギャラリーから投稿" /> | <img src="artwork/screenshots/03_location_picker.png" width="220" alt="位置情報の選択" /> |

| 投稿完了（時間・位置偽装） | RealMojiの保存 |
| :---: | :---: |
| <img src="artwork/screenshots/04_posted_result.png" width="220" alt="投稿結果" /> | <img src="artwork/screenshots/05_save_realmoji.png" width="220" alt="RealMoji保存" /> |

## 主な機能

- **ぼかし解除**: タイムラインや投稿のモザイクを解除して閲覧できます。
- **投稿・メディア保存**: 写真、動画（BTS）、RealMojiを高画質で端末に保存できます（撮影日時も保持）。
- **ギャラリー投稿**: 端末の写真や動画を選んでBeRealに投稿できます（切り抜き・位置情報・遅延投稿・公開範囲に対応）。
- **動画編集**: 縦3:4の切り抜き・回転、サムネイル付きタイムラインでのトリミング（最大30秒）に対応。内カメと外カメの動画の長さは自動で揃えられます。
- **広告非表示**: タイムライン上のスポンサー投稿や広告を非表示にします。
- **保存リスト**: 過去に読み込んだ投稿の履歴一覧から、いつでもダウンロードできます。

## 使い方

1. モジュールをインストールし、LSPosed等のフレームワークで有効化（またはNPatchでパッチ適用）して対象に **BeReal (`com.bereal.ft`)** を選択します。
2. BeRealを起動します。
3. ホーム画面のタイトル横にある **+** アイコンから、ギャラリー投稿や保存した投稿一覧、設定を開けます。各投稿のメニューからも保存できます。

> [!NOTE]
> **ログインについて**:
> - **v0.3.0 以降**: SMS認証（電話番号）によるログインにも対応しました。
> - **v0.2.0 以前、またはログインに失敗する場合の対処法**:
>   - モジュールが有効（フック）された状態では電話番号認証（SMSログイン）に失敗することがあります（メールログインは利用可能です）。
>   - SMSログインを行いたい場合は、先にモジュールを無効化した状態でログインを完了させてから有効化（フック）してください。
>   - NPatch等の埋め込み環境でログインできない場合は、**メールログイン**をお試しください。

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

## 保存した投稿のバックアップと統合

投稿URL・投稿者UID/名前・投稿/取得日時・キャプションをSQLiteの独立した列で管理します。**保存した投稿 → バックアップと統合**から`.sqlite`を書き出し、同じアカウントの別端末で内容を確認して統合できます。同じ投稿IDは重複させず、既存投稿を削除せずに統合し、URL候補と項目ごとの観測日時を保持します。未分類の旧データは確認してアカウントに関連付けてください。バックアップは暗号化されず署名付きURLを含みますが、画像・動画本体やログイン情報は含みません。ファイルによる手動統合で、自動同期ではありません。[仕様・制限](docs/archive-backup.md)を参照してください。

## 保存メディアのプレビュー

保存した投稿に内カメ・外カメの小さいプレビューを表示します。タップで全画面、左右スワイプで同じ投稿の内外カメラを切り替え、写真はピンチ／ダブルタップで拡大できます。カード上・全画面のどちらからも保存できます。動画は静止画プレビューを表示し、再生を押してから本体を読み込みます。黒白のMaterial/Tabler UI、カレンダー、SQLiteバックアップは維持しています。プレビューの一時データはバックアップに含みません。[仕様と検証](docs/saved-media-preview.md)。
