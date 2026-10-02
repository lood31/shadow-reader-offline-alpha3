# Redmi K80 离线发音反馈实验版 alpha3

日期：2026-10-02。版本：2.0.0-alpha3，versionCode=5，应用 ID `com.shadowreader.app`。这是可安装的 ARM64 实验包；K80 性能与安装升级验收尚未完成。

## 使用

从 GitHub Release 下载 `ShadowReader-2.0.0-alpha3-offline-debug.apk`，直接覆盖现有版本，保留原有文章、录音和训练数据。签名沿用工作区调试签名；实际升级保留数据仍待真机检查。安装包较大，建议至少预留 2GB 存储。第一次发音评估会流式解包和校验模型，初始化单独计时。

发音反馈默认在手机运行，无需启动电脑后端。录音后先显示发音证据，再串行运行已有 Whisper 内容对比；Whisper 模型沿用原有安装流程，未安装时仍可获得本地发音反馈。点击单词可查看 IPA、颜色原因及回放自己的对应录音片段，时间是估计位置。

绿表示目标支持较强，红表示竞争证据较强，黄表示证据有分歧，灰表示无法可靠评估。颜色不代表发音正确概率，数字发音分数为空。词级汇总保留可靠红色，否则采用 80% 可评估音素覆盖率；全部绿色才显示绿色。训练报告提供数量、覆盖率与最多三个重练重点。

“导出实验数据”由用户选择文件位置，ZIP 包含当前 PCM16 WAV、目标文本、结果 JSON、模型版本、初始化/推理/评分耗时、设备 RAM 和采样峰值 PSS。可靠性与竞争数值只出现在开发详情。失败保留录音并允许重试；切句、重新录音与退出取消旧任务，录音 ID 防止覆盖新结果。

## 已完成检查

- 120 条公开语料的 FP32/INT8 验证：PASS。验收口径与原始浮点差异详见 `offline-alpha3-stage1.md`；INT8 GOP MAE=0.07002、P95=0.31746、强优势方向一致率=99.465%。工程一致性结果不代表人类发音准确率。
- Python 证据与既有后端测试：22 通过；随机参考算法覆盖 2,520 次候选替换。
- Android JVM：34 项，33 通过、1 项外部服务测试跳过，0 失败。包括固定合成声学夹具的 Kotlin/Python 对照、颜色门槛、低可靠性、重复路径、闪音、覆盖率和旧协议兼容。
- 266 个唯一英语单词的桌面 C API 与现有 G2P 输出一致；Android 原生库成功编译。手机端 G2P 对照待运行。
- Android 编译、lint、原生库构建通过。打包前检查 PASS 回执、报告哈希及所有资源 SHA256。包内资源、ARM64 ABI、16KB ELF/ZIP 对齐及签名结果见 `docs/alpha3-package-validation.json`；APK 使用和 alpha2 一致的证书签名。

## K80 验收：尚未进行

开启 USB 调试并授权这台电脑，安装主 APK，然后在手机开启飞行模式。运行：

```powershell
.\scripts\benchmark-offline.ps1
```

专用测试 APK 随 Release 附带，或在本地构建于 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。它不修改训练数据库。测试使用5秒合成谐波波形；10/15/30 秒使用重复合成波形和重复文本，属于工程压力测试，不能代替真实长录音。测试记录首次初始化、266 个公开语料词条的桌面 G2P 对照、连续20次5秒评估、长录音、取消后恢复与100ms间隔采样 PSS。结果输出到 `artifacts/offline-k80-benchmark.json`。只有 warm 5秒 P95≤10000ms、峰值 PSS≤2GiB 时引擎性能门槛通过；低频采样可能遗漏瞬时峰值。

仍需人工检查：覆盖 alpha2 安装后文章/历史/录音保留；真实5/10/15/30秒录音；连续使用的降速、崩溃及内存不足；录音途中切句、重录、退出；无网络逐词查看、回放与导出；损坏资源/空间不足时保留录音并可恢复。手机完成这些检查前，不能宣称真机验收通过或保证10秒延迟。

## 构建与来源

工具链：JDK17、Gradle8.9、SDK35、NDK27.2.12479018、CMake3.22.1、ORT Android1.24.3。先按现有项目 README 配置 SDK/Gradle。

```powershell
.\backend\.venv\Scripts\python.exe -m pip install -r .\backend\requirements-offline.txt
# 在 backend 目录运行 export 和 validate；需要原固定权重与验收语料。
.\backend\.venv\Scripts\python.exe scripts/prepare-offline.py
.\scripts\build.ps1 -OfflinePronunciation
```

模型固定 `facebook/wav2vec2-lv-60-espeak-cv-ft` revision `ae45363bf3413b374fecd9dc8bc1df0e24c3b7f4`。导出 opset17；仅常量 MatMul/Gemm 动态 per-channel INT8。源权重与转换产物哈希记录在 `backend/models/offline/export-manifest.json`。源码包附报告与回执，完整 INT8/VAD/G2P 资源可运行 `python scripts/restore-offline-assets.py <APK路径>` 从交付 APK 校验并恢复，然后运行 `scripts/build.ps1 -OfflinePronunciation`；FP32 权重与公开验收语料需按原来源获取。本机完整缓存仍保留在 backend 中。

eSpeak NG 固定1.52.0，完整源码和 COPYING 随源码包交付；`.tools/espeak-ng-1.52.0.zip` 的哈希也随模型清单交付。自定义 Android CMake/JNI 位于 app 源码，上游源码未改动。构建脚本将源码 ZIP 放到 `.tools` 后使用 `third_party/espeak-ng-1.52.0`；源码包已包括该目录。

本实验构建与源码交付遵循 eSpeak NG 的 GPL-3.0-or-later 条款；上游代码、模型与数据分别保留原许可证，未上传或公开发布。公开源码与手机压力测试仅包含合成测试波形。APK 内带 GPL、Unicode、ORT MIT/第三方声明、Silero MIT、模型 Apache2.0 与模型卡；完整对应源码随本次交付。工作区原调试密钥不进入源码 ZIP，重建若需覆盖安装必须继续使用原工作区密钥。

上游来源：[eSpeak NG](https://github.com/espeak-ng/espeak-ng/tree/1.52.0)、[ONNX Runtime](https://github.com/microsoft/onnxruntime/tree/v1.24.3)、[Silero VAD](https://github.com/snakers4/silero-vad)、[模型固定版本](https://huggingface.co/facebook/wav2vec2-lv-60-espeak-cv-ft/tree/ae45363bf3413b374fecd9dc8bc1df0e24c3b7f4)。
