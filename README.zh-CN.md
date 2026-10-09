# BeFuck

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

BeReal 的 Xposed 功能增强模块。  
支持 **LSPosed** 与 **NPatch**。  
支持版本：**3.97.0 (3597523)**（`com.bereal.ft`）。

## 屏幕截图

| 去除模糊与保存 | 相册发布 | 位置选择 |
| :---: | :---: | :---: |
| <img src="artwork/screenshots/01_unblur_download.png" width="220" alt="去除模糊与保存" /> | <img src="artwork/screenshots/02_gallery_post.png" width="220" alt="相册发布" /> | <img src="artwork/screenshots/03_location_picker.png" width="220" alt="位置选择" /> |

| 发布效果（时间与位置伪装） | 保存 RealMoji |
| :---: | :---: |
| <img src="artwork/screenshots/04_posted_result.png" width="220" alt="发布效果" /> | <img src="artwork/screenshots/05_save_realmoji.png" width="220" alt="保存 RealMoji" /> |

## 主要功能

- **去除模糊**：无需发帖即可查看被模糊的时间线和帖子。
- **保存媒体**：将照片、视频（BTS）和 RealMoji 原画保存至设备（保留拍摄时间）。
- **相册发布**：直接选择相册中的照片或视频发布到 BeReal（支持裁剪、位置、迟发和公开范围设置）。
- **广告拦截**：隐藏信息流中的推广与赞助内容。
- **历史记录**：随时查看并下载此前加载过的帖子。

## 安装与使用

1. 安装模块并在 LSPosed 等框架中启用（或使用 NPatch 修补 APK），将作用域勾选为 **BeReal (`com.bereal.ft`)**。
2. 打开 BeReal。
3. 点击主页标题旁的 **+** 图标，即可使用相册发布、已保存帖子和设置。在帖子菜单中也可直接保存。

> [!NOTE]
> **登录说明**：
> - **v0.3.0 及更高版本**：已修复并支持手机号短信（SMS）验证码登录。
> - **v0.2.0 及更早版本，或登录失败时的解决方法**：
>   - 模块处于生效（已 Hook）状态下时，手机号短信验证码登录可能会失败（邮箱登录不受影响）。
>   - 若需要使用短信验证码登录，请先在禁用模块的状态下完成登录，然后再启用（Hook）该模块。
>   - 在 NPatch 等嵌入式环境下若无法正常登录，请尝试使用**邮箱登录**。

保存路径：`Pictures/BeFuck` 或 `Movies/BeFuck`。

## 构建

需要 JDK 17+ 和 Android SDK。

**Windows (PowerShell)**

```powershell
.\gradlew.bat :app:assembleDebug
```

**Linux / macOS**

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

输出文件：`app/build/outputs/apk/debug/app-debug.apk`

## 许可证

Copyright 2026 **tqmane**。[PolyForm Noncommercial 1.0.0](LICENSE)。
