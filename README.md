# BeFuck

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

An Xposed module for BeReal.  
Supported BeReal version: **3.97.0 (3597523)** (`com.bereal.ft`).

## Features

- **Unblur**: View blurred posts and timeline cards without restrictions.
- **Save Media**: Save photos, videos, BTS, and RealMojis directly to your device with original timestamps.
- **Gallery Posting**: Post photos or videos directly from your gallery (supports cropping, captions, location, and late posting).
- **Ad Blocker**: Hide sponsored posts and feed ads.
- **Post History**: View and download previously loaded posts anytime.

## How to Use

1. Install and enable the module in LSPosed (or compatible framework), selecting **BeReal (`com.bereal.ft`)** in the scope.
2. Open BeReal.
3. Tap the **+** button beside the Home feed title to access gallery posting, saved posts, and settings. You can also save directly from post menus.

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
