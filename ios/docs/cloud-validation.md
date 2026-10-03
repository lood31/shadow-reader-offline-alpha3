# macOS 云端验证记录

本记录与 Windows 静态检查快照 `validation.json` 分开保存；静态检查不代表 Xcode 编译或运行成功。

## 已验证结果

- 日期：2026-10-03；源码提交：`cf4189ddc98bc0abd9092e306736dd725f35f8d8`。
- [GitHub Actions 成功运行 37090574612](https://github.com/lood31/shadow-reader-offline-alpha3/actions/runs/37090574612)，完整日志、锁文件和 `Tests.xcresult` 保存为该运行的构建附件。
- macOS 15 托管环境；Xcode 16.4（16F6），iOS SDK 18.5；iPhone 16 Pro / iOS 18.5 模拟器（arm64）。最低系统仍为 iOS 17；iOS 17 运行验收尚待执行。
- 原生依赖：真机 arm64、模拟器 arm64/x86_64 静态库及 XCFramework 构建成功；ONNX Runtime 1.24.3、whisper.cpp 1.9.4、eSpeak NG 1.52.0。
- 模型资源包及 375 个资源文件的大小与 SHA-256 校验通过，6 项 Python 资源工具测试通过。
- 真机架构无签名 Release 编译与链接成功；模拟器 Debug 编译、链接及 XCTest 成功。
- **20 项 XCTest 全部通过，0 失败、0 跳过**：Core 13、Evidence 3、NativeRuntime 2、Store 2。
- 原生测试实际加载本地 ONNX/VAD 模型，完成 266 词 G2P 对照，以及取消加载后下一请求正常恢复。声学证据算法与固定样本对照通过；此结论不包含真实录音上的端到端模型推理。

随后仅更新此验收文档，不修改已验证的源码、工程或构建配置。

## 修复范围

- 补齐 eSpeak 的 CMake 构建输入，并为 Darwin 提供小端字节序转换。
- 拆分 SwiftUI 状态声明及复杂报告视图，修复首次 Xcode 类型检查发现的问题。
- 模拟器 Debug 使用当前架构，保证 Swift 包与应用目标一致。
- 测试进程显式开启原生测试，保留所有对照样本。
- UTF-16 位置必须落在有效 Unicode 边界，拒绝截断 emoji 的位置。
- 模型词表按原始 UTF-8 字节查找，保留 Swift 原本会视为等价的两个 IPA token 及其独立编号。
- eSpeak 的 Apple 路径缓冲区扩大为 1024 字节，支持长模拟器容器路径；初始化错误不得终止应用进程。

## 尚待验收

云端单元测试不能代替 `device-checklist.md` 中的交互流程。Safari 分享签名、麦克风录音、音频中断、真实录音上的 ONNX/Whisper 推理、飞行模式、内存峰值和持续训练均需另行验收。无签名真机架构编译不等于已安装到 iPhone。
