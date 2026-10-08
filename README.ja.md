# BeFuck

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

BeRealの機能を拡張するXposedモジュールです。  
**LSPosed** および **NPatch** に対応しています。  
対応バージョン：**3.97.0 (3597523)**（`com.bereal.ft`）

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
- **広告非表示**: タイムライン上のスポンサー投稿や広告を非表示にします。
- **保存リスト**: 過去に読み込んだ投稿の履歴一覧から、いつでもダウンロードできます。

## 使い方

1. モジュールをインストールし、LSPosed等のフレームワークで有効化（またはNPatchでパッチ適用）して対象に **BeReal (`com.bereal.ft`)** を選択します。
2. BeRealを起動します。
3. ホーム画面のタイトル横にある **+** アイコンから、ギャラリー投稿や保存した投稿一覧、設定を開けます。各投稿のメニューからも保存できます。

> [!WARNING]
> **ログインに関する注意**:
> - モジュールが有効（フック）された状態では電話番号認証（SMSログイン）に失敗します（メールログインは利用可能です）。
> - SMSログインを利用したい場合は、先にモジュールを無効化した状態でログインを完了させてから有効化（フック）してください。
> - NPatch等の埋め込み環境では後からフックを切り替えられないため、**メールログインのみ**利用可能です。

保存先：端末の `Pictures/BeFuck` または `Movies/BeFuck`

## ビルド

GradleデーモンはJDK 25を使用します（`gradle/gradle-daemon-jvm.properties`）。JDK 25とAndroid SDK Platform 37を用意してください。Javaソースの互換性は17です。

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

### GitHub Actions

**Actions → Debug APK → Run workflow** からブランチを選んでビルドできます（ワークフローが`main`にマージされた後に利用可能）。PRと`main`へのpushでも自動実行します。実行結果の **Artifacts** にある `BeFuck-debug-<実行番号>` から `app-debug.apk` をダウンロードできます。保存期間は14日です。モジュール検査、Roomスキーマの回帰検査、Android lintも実行し、レポートを別途保存します。APKは自動生成したデバッグ鍵で署名するため、別ビルドのAPKへ上書きするには署名鍵を合わせる必要がある場合があります。リリース署名用のSecretは不要です。

SQLスキーマだけをローカルで検査するには `python3 checks/check_room_schema.py` を実行します。CIの成功は実機でのLSPosed/NPatch動作確認を意味しません。

## ライセンス

Copyright 2026 **tqmane**。[PolyForm Noncommercial 1.0.0](LICENSE)。
