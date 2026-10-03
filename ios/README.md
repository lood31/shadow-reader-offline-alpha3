# 影子阅读器 iPhone 客户端

这是独立的 SwiftUI 开发工程，最低 iOS 17，优先 iPhone，iPad 使用兼容布局。当前交付为 Windows 上完成的源码与静态验证，**尚未经过 Xcode 类型检查、链接、模拟器运行或 iPhone 验收**。Android 实现未改动。

## 已实现的源码范围

- HTTPS 网页提取、粘贴、导入预览、素材库、训练位置保存、Safari 分享扩展与 App Groups 收件箱。
- 四款 Edge 英语音色与 Apple 英语语音、试听、缓存、0.5–2 倍速、循环与按实际播放时间计算的留白；支持暂停、停止和跳过留白。
- 手动 M4A 录音、单声道 16 kHz 分析、本机发音评估、Whisper 辅助内容比较、重录与重新分析、录音及估计音素片段回放。
- `schemaVersion=2` 声学证据：逐词及逐音素绿黄红灰、UTF-16 高亮、IPA、覆盖率、空数字分数、WAV/JSON/目标文本 ZIP 导出。
- SQLite 保存文章、会话、尝试与复习；报告按每句最终结果去重，支持难句、到期复习、内容对比中的识别争议回退。

发音模式始终手动推进，辅助 Whisper 不决定发音颜色或掌握状态。内容模式按原规则更新复习，并可在一致后两秒进入下一句。无声、过短、超限和失败的录音保留，结果显示未评估。发音模型支持 0.5–30 秒、不超过 120 个词/400 个音素；录音最长两分钟，超过发音限制仍可保留。

语音生成、音频、识别、发音评估和存储各有独立接口。`LocalInference` 的物理串行队列在原生推理退出和清理之前不会放行下一项；取消通过尝试 ID 传入 ONNX/Whisper。控制器另用版本号及尝试 ID 隔离旧结果，播放器也隔离旧取消回调。

## Windows 验证与资源准备

在项目根目录执行，Python 3.10 以上：

```powershell
python ios/scripts/prepare_resources.py
python ios/scripts/generate_project.py
python ios/scripts/test_resource_tools.py
python ios/scripts/validate.py
```

资源脚本沿用本项目已有 Android alpha3 资产及导出校验记录，不重新量化模型、不下载 Whisper。当前已准备 375 个模型/VAD/G2P/许可证文件，合计 377,657,194 字节。清单和校验凭据见 `Resources/pronunciation/manifest.json` 与 `Resources/resource-receipt.json`。

可选 Swift 语法和 OpenStep 工程解析检查，依赖仅安装到工程内的虚拟环境：

```powershell
python -m venv ios/.qa
ios/.qa/Scripts/python.exe -m pip install tree-sitter==0.26.0 tree-sitter-swift==0.7.3 openstep-parser==2.0.3
ios/.qa/Scripts/python.exe ios/scripts/validate.py --syntax
bash -n ios/scripts/build_native.sh
```

语法解析不等于 Swift 类型检查。检查记录见 `docs/validation.md` 和机器可读的 `docs/validation.json`。

## 拿到 Mac 后构建

也可用 GitHub Actions 的云端 macOS 执行无签名构建和模拟器测试，流程见 `docs/github-actions.md`；无需先配置本机虚拟机。云端结果仍不能替代 iPhone 验收。

需要已安装完整 Xcode、对应 iOS SDK、Python 3、CMake 3.22 以上和 CocoaPods 的 Mac；环境要求以 [Apple 官方说明](https://developer.apple.com/xcode/system-requirements) 为准。ONNX 的苹果构建要求见 [官方文档](https://onnxruntime.ai/docs/build/ios.html)。

迁移时保留目录关系：`ios/`、`third_party/whisper.cpp-1.9.4/`、`third_party/espeak-ng-1.52.0/`。模型资源不在版本控制中，须一并复制已校验的 `ios/Resources/pronunciation/` 和收据。若要在 Mac 重新准备资产，则同时复制 Android 资源、测试样本和 `backend` 中的导出清单/校验报告。

在项目根目录执行：

```bash
python3 ios/scripts/validate.py --resources-only
bash ios/scripts/build_native.sh
cd ios
pod install
open ShadowReader.xcworkspace
```

原生脚本以 CPU 为基线，构建真机 arm64、模拟器 arm64/x86_64 的 whisper.cpp 1.9.4 与 eSpeak NG 1.52.0 静态 XCFramework。没有运行上游会删除目录的脚本；已有输出会移动为带时间戳备份。ONNX Runtime 由 `Podfile` 固定为 `onnxruntime-c` 1.24.3。SwiftSoup 2.6.0、ZIPFoundation 0.9.19 固定为精确版本。**当前未执行 `pod install`，未生成或伪造 Podfile.lock 和原生库。** 首次安装后保留真实锁文件。

打开 CocoaPods 生成的 `.xcworkspace`，选择 `ShadowReader` scheme。模拟器首次验证可关闭签名：

```bash
xcodebuild -workspace ShadowReader.xcworkspace -scheme ShadowReader -showdestinations
# 将下面的 DEVICE_UUID 替换为上一命令列出的 iOS 17+ 模拟器 UUID。
xcodebuild -workspace ShadowReader.xcworkspace -scheme ShadowReader \
  -destination 'platform=iOS Simulator,id=DEVICE_UUID' \
  CODE_SIGNING_ALLOWED=NO test
```

Swift 与 Objective-C++ 均未在本阶段编译，Mac 首次构建是下一道必需验收门槛；遇到编译、链接或 SDK 差异需先修复再记录通过。纯算法和存储测试默认运行。266 词原生 G2P 与取消恢复测试默认跳过：在 Xcode 的 Edit Scheme → Test → Arguments → Environment Variables 添加 `RUN_NATIVE_IOS_TESTS=1`，然后重跑测试；普通 Shell 环境变量不保证传到测试进程。

## 签名及 Safari 分享

主应用和 `ShadowReaderShare` 需使用同一 Team，并各自设置唯一 Bundle Identifier。在 Signing & Capabilities 中为两个目标配置同一个 App Groups ID，并将两者 Build Settings 的 `SHADOW_APP_GROUP` 改成这个 ID。默认占位为 `group.com.shadowreader.ios`，不代表已注册；工程未填 Team、证书或账户。

分享扩展只接收 URL/文本，原子写入共享收件箱并退出。用户打开主应用后预览，再保存到素材库；它不在扩展进程加载模型，也不自动拉起主应用。

若签名账户无法使用 App Groups，可先验证模拟器；要临时构建仅主应用，在 Xcode 中移除主目标的 `Embed App Extensions` 构建阶段及 `ShadowReaderShare` 目标依赖，清空主目标 `CODE_SIGN_ENTITLEMENTS`。网页/粘贴功能仍可使用，Safari 分享暂时不能验收。不要把这种临时配置记为完整分享验收通过。工程生成器会恢复默认完整配置，签名改完后不要再次生成工程。

## 模型、隐私与许可证

发音资产和校验值沿用现有清单，首次分析逐文件校验。Whisper 模型由设置页主动下载并检查大小与 SHA-256：

```text
ggml-base.en-q5_1.bin
bytes: 59721011
sha256: 4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f
```

下载 URL 固定到现有 Hugging Face 仓库修订，不随主分支变化。Whisper 启用前再次校验；麦克风音频、推理、结果和数据库留在本机。Edge 只发送待朗读文本，网页导入只请求用户输入的网页，模型下载只获取模型。导出的 ZIP 在用户主动选择系统分享前保留在沙盒中。离线听原句需要缓存或系统已安装的英语语音。

发音资产的许可证保留在 `Resources/pronunciation/licenses/`，原生源码许可证保留在 `third_party/`。eSpeak NG 为 GPL 组件；本交付是自用开发工程，公开分发、签名发布及许可证分发安排未包含在本阶段。

## 后续验收

按 `docs/device-checklist.md` 先完成 Mac/Xcode，再完成 iPhone 实测。导出中的内存指标是结束时的物理内存占用快照，**不是峰值、不是 Android PSS**；首载时间、延迟和峰值需在真机用 Instruments 单独测量。模拟器的录音、离线性、速度和内存结果不能代替真机记录。

不包含 Android 数据搬家、云同步、后台播放、Mac 专用界面、TestFlight 或 App Store 发布。
