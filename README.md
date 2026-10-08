# BeFuck

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

An Xposed module for BeReal.  
Supports **LSPosed** and **NPatch**.  
Supported BeReal version: **3.97.0 (3597523)** (`com.bereal.ft`).

## Screenshots

| Unblur & Download | Gallery Posting | Location Picker |
| :---: | :---: | :---: |
| <img src="artwork/screenshots/01_unblur_download.png" width="220" alt="Unblur and Download" /> | <img src="artwork/screenshots/02_gallery_post.png" width="220" alt="Gallery Posting" /> | <img src="artwork/screenshots/03_location_picker.png" width="220" alt="Select Location" /> |

| Posted Result (Time & Location Spoof) | Save RealMojis |
| :---: | :---: |
| <img src="artwork/screenshots/04_posted_result.png" width="220" alt="Posted Result" /> | <img src="artwork/screenshots/05_save_realmoji.png" width="220" alt="Save RealMojis" /> |

## Features

- **Unblur**: View blurred posts and timeline cards without restrictions.
- **Save Media**: Save photos, videos (BTS), and RealMojis directly to your device with original timestamps.
- **Gallery Posting**: Post photos or videos directly from your gallery (supports cropping, captions, location, and late posting).
- **Ad Blocker**: Hide sponsored posts and feed ads.
- **Post History**: View and download previously loaded posts anytime.

## How to Use

1. Install and enable the module in LSPosed (or embed/patch with NPatch), selecting **BeReal (`com.bereal.ft`)** in the scope.
2. Open BeReal.
3. Tap the **+** button beside the Home feed title to access gallery posting, saved posts, and settings. You can also save directly from post menus.

> [!WARNING]
> When using NPatch, only **email login** is supported (SMS login is not supported).

Saved files location: `Pictures/BeFuck` or `Movies/BeFuck`.

## Build

Requires JDK 17+ and Android SDK.

**Windows (PowerShell)**

```powershell
.\gradlew.bat :app:assembleDebug
```

**Linux / macOS**

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## License

Copyright 2026 **tqmane**. [PolyForm Noncommercial 1.0.0](LICENSE).
