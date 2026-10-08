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

> [!WARNING]
> **登录注意事项**：
> - 在模块生效（已 Hook）状态下，手机号短信验证码登录会失败（邮箱登录不受影响）。
> - 若需要使用短信验证码登录，请先在未启用模块的状态下完成登录，然后再启用（Hook）该模块。
> - 在 NPatch 等嵌入式环境下由于无法在登录后切换 Hook，因此仅支持**邮箱登录**。

保存路径：`Pictures/BeFuck` 或 `Movies/BeFuck`。

## 构建

Gradle 守护进程使用 JDK 25（见 `gradle/gradle-daemon-jvm.properties`）。请安装 JDK 25 和 Android SDK Platform 37；Java 源码兼容级别仍为 17。

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

### GitHub Actions

在 **Actions → Debug APK → Run workflow** 中选择分支即可构建（工作流合并到 `main` 后可用）。PR 和推送到 `main` 也会自动构建。在运行结果的 **Artifacts** 中下载 `BeFuck-debug-<运行编号>`，其中包含 `app-debug.apk`，保留14天。CI 还会执行模块检查、Room 数据库结构回归检查和 Android lint，并单独上传报告。APK 使用自动生成的调试签名，覆盖安装其他构建时可能需要使用相同的签名密钥；无需发布签名 Secret。

本地检查 SQL 结构：`python3 checks/check_room_schema.py`。CI 成功不代表已验证真机上的 LSPosed/NPatch 运行情况。

## 许可证

Copyright 2026 **tqmane**。[PolyForm Noncommercial 1.0.0](LICENSE)。
