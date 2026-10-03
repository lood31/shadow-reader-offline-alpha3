# GitHub 云端 macOS 构建

目标仓库：`lood31/shadow-reader-offline-alpha3`。新增客户端是实验源码；云端编译结果和真机验收分别记录，不预先标记成功。

工作流 `.github/workflows/ios-macos.yml` 在 iOS/原生依赖变动推送时自动执行，也支持 Actions 页手动触发。使用 GitHub 托管 `macos-15` 的默认 Xcode；每次记录真实 Xcode/SDK 版本。流程如下：

1. 下载本仓库 `ios-resources-v1` Release 中的 `shadowreader-pronunciation-v1.zip`；校验整包、清单及每个资源的 SHA-256。
2. 构建 whisper.cpp/eSpeak 的真机与模拟器静态库，安装固定版本 ONNX Pod 与 Swift 包。
3. 编译无签名 Release 真机目标，运行模拟器 XCTest（包括原生 G2P 与取消恢复测试）。
4. 上传编译日志、真实 Podfile.lock 与 `.xcresult`，保存七天；失败也保留已经产生的日志。

源码进入 Git，模型包进入 Release，不把 356 MB 单模型写入普通 Git。云端读取 Release 只使用 Actions 内置 `GITHUB_TOKEN`，不需要另填模型秘钥或苹果证书。此流程不产生可安装到 iPhone 的已签名 IPA，不执行 TestFlight/App Store 发布。

首次推送前需要先上传已经校验的资源包，然后推送工作流和源码。用户授权的资源在 Android 离线版已使用；这里不重新下载或量化。

```powershell
# 项目根目录。重复打包会更新资源契约，所以打包后须一起提交该契约。
python ios/scripts/ci_resources.py package ios/.build/ci/shadowreader-pronunciation-v1.zip
gh auth login -h github.com --web
gh release create ios-resources-v1 ios/.build/ci/shadowreader-pronunciation-v1.zip --repo lood31/shadow-reader-offline-alpha3 --title "iOS offline resources v1" --notes "Exact Android alpha3 model/VAD/G2P/license resources for experimental iOS CI. SHA-256 recorded in ios/Resources/ci-resource-bundle.json; iPhone validation pending."
# Release 已存在时先比较实际资产哈希，不覆盖不同内容或使用 --clobber。
```

查看日志：仓库 Actions → **iOS macOS build and tests** → 对应提交。若失败，先确认资源 Release 存在，再按最早的原生构建/Pod/Swift 编译/测试错误修复。云端构建成功后依然需要 iPhone 离线、权限、中断、连续评估与性能验收。
