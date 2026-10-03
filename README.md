# 影子阅读器 · Shadow Reader

跟着英语原句听、录、比对、重练，把文章变成自己的听读训练素材。

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/lood31/shadow-reader-offline-alpha3/releases/tag/v2.0.0-alpha3)
[![Version](https://img.shields.io/badge/版本-2.0.0--alpha3-orange)](https://github.com/lood31/shadow-reader-offline-alpha3/releases/tag/v2.0.0-alpha3)
[![iOS](https://img.shields.io/badge/iOS-17%2B_开发中-007AFF?logo=apple&logoColor=white)](https://github.com/lood31/shadow-reader-offline-alpha3/tree/ios/macos-ci/ios)
[![iOS build](https://github.com/lood31/shadow-reader-offline-alpha3/actions/workflows/ios-macos.yml/badge.svg?branch=ios%2Fmacos-ci)](https://github.com/lood31/shadow-reader-offline-alpha3/actions/workflows/ios-macos.yml?query=branch%3Aios%2Fmacos-ci)

**[下载 Android APK](https://github.com/lood31/shadow-reader-offline-alpha3/releases/download/v2.0.0-alpha3/ShadowReader-2.0.0-alpha3-offline-debug.apk) · [Android Release 说明](https://github.com/lood31/shadow-reader-offline-alpha3/releases/tag/v2.0.0-alpha3) · [全部 Releases](https://github.com/lood31/shadow-reader-offline-alpha3/releases) · [iPhone 开发工程](https://github.com/lood31/shadow-reader-offline-alpha3/blob/ios/macos-ci/ios/README.md)**

> 当前 Android 版本为 **V2 alpha3 离线发音实验版**，APK 约 419 MB，适用于 ARM64 手机。iPhone 版已通过云端编译和模拟器自动测试，暂未提供可安装的 IPA。

## 下载与安装

| 文件 | 用途 |
| --- | --- |
| [Android 主应用 APK](https://github.com/lood31/shadow-reader-offline-alpha3/releases/download/v2.0.0-alpha3/ShadowReader-2.0.0-alpha3-offline-debug.apk) | 安装、听读与离线反馈训练 |
| [SHA-256 校验清单](https://github.com/lood31/shadow-reader-offline-alpha3/releases/download/v2.0.0-alpha3/ShadowReader-2.0.0-alpha3-SHA256SUMS.txt) | 检查下载文件是否完整 |
| [Android 对应源码包](https://github.com/lood31/shadow-reader-offline-alpha3/releases/download/v2.0.0-alpha3/ShadowReader-2.0.0-alpha3-source.zip) | alpha3 源码及第三方许可 |
| [专用 benchmark APK](https://github.com/lood31/shadow-reader-offline-alpha3/releases/download/v2.0.0-alpha3/ShadowReader-2.0.0-alpha3-benchmark.apk) | 开发者压力测试，普通使用无需下载 |

下载主应用 APK，在手机上允许安装该来源的应用后安装。已有同签名版本时可覆盖安装；卸载会清除应用数据。实际升级的数据保留仍需真机验收，重要素材请先保留原文。建议预留至少 2 GB 存储空间。

Android 下载入口固定指向 `v2.0.0-alpha3`。仓库另有 [iOS 离线资源 Release](https://github.com/lood31/shadow-reader-offline-alpha3/releases/tag/ios-resources-v1)，其中的 ZIP 是模型资源，不是手机安装包。

## 训练流程

**导入文章 → 听原句 → 手动录音 → 离线发音反馈 → Whisper 内容比对 → 重练 → 训练报告 → 到期复习**

1. 通过网页、浏览器分享或粘贴导入英语正文，预览后保存到素材库。
2. 听原句，按需要调整音色、速度、循环与留白。
3. 手动开始和结束录音，查看逐词、逐音素证据；点击单词查看 IPA 与估计录音片段。
4. 若需要内容比对，在设置中主动下载 Whisper 模型；未下载也可使用本地发音反馈。
5. 针对重点再读一次，结束训练查看报告，之后从到期复习入口继续练习。

## 主要功能

- **素材管理**：网页、粘贴及分享导入，文章预览，本地素材库与训练进度。
- **听读控制**：0.5×–2× 调速、循环、自适应留白、四种 Edge 英语音色及系统语音，支持语音缓存。
- **离线发音证据**：INT8 ONNX 模型、Silero VAD、英语 G2P 随 Android APK 内置，无需电脑后端。
- **内容比对**：Whisper 在手机识别录音，展示一致、漏词、替换和多词，支持识别争议与重练。
- **训练与复习**：按每句最终结果生成报告，保留难句与到期复习；发音模式手动推进。
- **实验导出**：导出录音、目标文本、结果 JSON 及模型信息，便于检查和复现。

### 反馈怎样读

| 颜色 | 含义 |
| --- | --- |
| 🟢 绿 | 目标音素支持较强 |
| 🟡 黄 | 证据存在分歧 |
| 🔴 红 | 竞争音素证据较强 |
| ⚪ 灰 | 无法可靠评估 |

这是实验性声学证据，**不代表发音正确概率，数字发音分数保持为空**。内容一致只说明识别文本相符；录音片段边界是估计位置。静音、过短、失败或取消时保留录音并显示未评估。

## 离线与隐私

麦克风录音、发音分析、Whisper 识别和训练数据保存在设备上，不上传录音。网页导入、Edge 语音合成和首次 Whisper 模型下载需要网络；Edge 会接收待朗读文本。离线听原句需要已缓存的语音或已安装的系统英语语音。

首次发音评估会解包并校验内置资源。Whisper 模型由用户主动下载，约 60 MB，下载完成后检查 SHA-256。

## 平台与验证状态

| 平台 | 当前交付 | 已完成验证 | 待完成 |
| --- | --- | --- | --- |
| Android | V2 alpha3 ARM64 调试 APK | 模型对照、单元测试、编译及静态安装包检查 | 实际升级、飞行模式、录音交互、延迟及内存验收 |
| iPhone | iOS 17+ SwiftUI 源码，位于 `ios/macos-ci` 分支 | 云端真机架构无签名编译；iOS 18.5 模拟器 20 项测试通过、零跳过 | 签名安装、Safari 分享、录音、离线训练与性能验收 |

详见 [Android 验证与构建说明](docs/offline-alpha3-validation.md)、[Android 真机清单](docs/device-checklist.md)和 [iOS 云端验证记录](https://github.com/lood31/shadow-reader-offline-alpha3/blob/ios/macos-ci/ios/docs/cloud-validation.md)。[成功的 iOS 云端运行](https://github.com/lood31/shadow-reader-offline-alpha3/actions/runs/37090574612)包括 266 词 G2P 对照和取消后的恢复测试；模拟器结果不能替代真机验收。

## 从源码构建

### Android

工具链：JDK 17、Gradle 8.9、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1、ONNX Runtime 1.24.3。仓库不包含模型权重和私人录音。

在已配置 Android 工具链的环境中，从 Release 下载主 APK，然后恢复经过校验的发音资源：

```powershell
python scripts/restore-offline-assets.py <下载的APK路径>
```

Windows 可使用项目脚本构建离线版本；它需要 `backend/.venv` 中安装 `backend/requirements-offline.txt` 所列的依赖：

```powershell
.\scripts\build.ps1 -OfflinePronunciation
```

首次环境配置、模型来源与完整步骤见 [Android 构建说明](docs/offline-alpha3-validation.md)。自己生成的调试签名不能保证覆盖安装现有 Release。

### iPhone

切换到 `ios/macos-ci` 分支，按照 [iOS README](https://github.com/lood31/shadow-reader-offline-alpha3/blob/ios/macos-ci/ios/README.md)准备资源、构建原生库和配置 Xcode。也可查看 [macOS 自动构建流程](https://github.com/lood31/shadow-reader-offline-alpha3/blob/ios/macos-ci/.github/workflows/ios-macos.yml)。签名和 App Groups 需自行配置；当前不包含 TestFlight 或 App Store 分发。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `app/` | Kotlin / Compose Android 客户端 |
| `ios/` | SwiftUI iPhone 客户端，当前在 `ios/macos-ci` 分支 |
| `backend/` | 模型导出、验证与早期实验后端；alpha3 手机发音评估无需运行它 |
| `scripts/` | 构建、资源恢复、打包与验证工具 |
| `docs/` | 验证记录与设备验收清单 |
| `third_party/` | whisper.cpp、eSpeak NG 等固定版本原生依赖 |

## 许可证与来源

eSpeak NG 为 GPL-3.0-or-later；相关源码与许可随仓库及源码包保留，见 [GPL 文本](COPYING-alpha3-GPL-3.txt)和 [源码分发说明](SOURCE-DISTRIBUTION.txt)。Whisper、ONNX Runtime、Silero VAD、模型及其他第三方组件分别遵循各自许可证。Edge 语音协议实现并非微软官方 SDK。

本项目用于自用与实验，发音反馈和设备性能仍在验证中。
