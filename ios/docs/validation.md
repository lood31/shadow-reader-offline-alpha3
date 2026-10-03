# Windows 交付验证记录

日期：2026-10-02。环境：Windows / PowerShell / Python；无 Mac、Xcode 或 iPhone。

| 检查 | 结果与范围 |
|---|---|
| 375 个已准备资源的大小与 SHA-256 | 通过，合计 377,657,194 字节；保留原模型、VAD、词表、G2P 和许可证 |
| 原导出清单及桌面模型校验报告链 | 通过；这是已有桌面资产凭据，不是 iOS 模型推理证明 |
| Xcode 工程对象及源文件引用 | 通过；主应用、分享扩展、XCTest 三个目标，最低 iOS 17 |
| OpenStep pbxproj、plist、entitlements、scheme | 通过；App Groups 一致，麦克风说明存在 |
| Swift 文件语法解析 | 21 个文件通过 tree-sitter；未执行类型检查或链接 |
| Python 脚本语法 | 通过 |
| 资源失败路径回归 | 6 项通过：合法、同大小损坏、截断、父目录越界、绝对路径越界、文件缺失 |
| Mac 原生构建脚本 | `bash -n` 通过；未执行苹果构建 |
| SPM 精确版本与调用接口 | 从固定官方标签源码核对 SwiftSoup 2.6.0 与 ZIPFoundation 0.9.19；未进行 Swift 包编译 |
| CocoaPods / ONNX Runtime 1.24.3 | Podfile 与原生版本门槛已固定；安装、切片、链接仍待 Mac 验证 |
| XCTest | 提供 19 项，未运行；原生 G2P 与取消恢复项需显式开启 |
| Xcode / 模拟器 / iPhone / iOS 模型一致性 / 性能 | 全部待验证 |

执行命令：

```text
backend/.venv/Scripts/python.exe ios/scripts/extend_g2p_fixture.py
ios/.qa/Scripts/python.exe ios/scripts/test_resource_tools.py
ios/.qa/Scripts/python.exe ios/scripts/validate.py --syntax
bash -n ios/scripts/build_native.sh
```

当前 G2P 原始测试表实际是 **263** 词，原文档的“266”与文件不一致。原 263 词逐字节保留；另外 3 词由本机 eSpeak NG 1.52.0 C API、`en-us` 音色及同一份已准备数据实际生成，共形成 266 词。补充结果与来源分别记录在 `Tests/Fixtures/g2p-supplement.json`、`g2p-provenance.json`；苹果平台尚未执行这些词的对照。

| 凭据 | SHA-256 |
|---|---|
| 发音 manifest.json | `284addb9cef058d4351a071edd2df2422a05ebb79a7332e2f776d0a206106104` |
| 原桌面 parity report.json | `418b8aacc0c924f4e3f105d14a7d1bf06177bc75992b06abf9a89ae6f8a8b2c2` |

固定官方包源码核对：SwiftSoup 2.6.0 解引用提交 `0e96a20ffd37a515c5c963952d4335c89bed50a6`；ZIPFoundation 0.9.19 提交 `02b6abe5f6eef7e3cbd5f247c5cc24e246efcfe0`。用于核对的临时源码在 `.build/qa-sources/`，不属于客户端运行依赖。

机器可读结果：`validation.json`。`PASS_STATIC_ONLY` 仅表示上述静态门槛通过，不表示应用已经能安装运行。
