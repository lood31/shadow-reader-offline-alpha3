# V1.1 验证记录

验收记录日期：2026-10-01。**实现与本地构建已完成，真机验收尚未完成。**

| 检查 | 结果 |
| --- | --- |
| 完整构建 | `scripts/build.ps1` 通过；最终 `BUILD SUCCESSFUL`，包含原生库，没有使用 skipNative |
| 自动测试 | 22 项通过，0 失败 / 错误 / 跳过；其中 21 项离线 JVM 测试、1 项可选联网集成测试 |
| Lint | 0 错误、8 条已有依赖更新提示，没有使用 baseline 或关闭错误检查 |
| 数据库迁移 | 实际 MIGRATION_1_2 SQL 在 SQLite 执行，与 Room 导出 v2 的字段 / 索引 / 外键一致；保留 v1 文章、句子、旧进度，旧进度未转成尝试；孤立尝试被拒绝，级联删除通过 |
| Edge 在线协议 | 应用中的 Kotlin EdgeSpeech 实际调用 Aria / Guy / Sonia / Ryan，统一样句均生成 MP3；另生成漏词 / 替换 / 多词样句 |
| 音频文件格式 | ffprobe 检查七个样句均为 24 kHz 单声道 MP3；此项不是手机听感或发音效果验收 |
| Whisper 原生构建 | v1.9.4；arm64-v8a / armeabi-v7a / x86_64 三种库均编译并打入最终 APK |
| JNI 接口 | arm64 ELF 中存在 create / transcribe / cancel / free 四个导出；自身库 LOAD 页对齐 0x4000 |
| 模型下载来源 | 桌面已下载固定修订的 base.en Q5_1，59,721,011 字节，SHA-256 校验通过；应用下载 / 取消界面尚未在手机运行 |
| APK 签名 | 最终包 apksigner verify 通过，v2 签名；证书 SHA-256 与 V0.1 完全相同 |
| APK 对齐 | zipalign -c -p 4 通过；未把此项解释为所有设备上的 16 KB 兼容验收 |
| 包信息 | com.shadowreader.app，versionCode 2 / versionName 1.1.0，minSdk 26 / targetSdk 35 |
| 真机安装与运行 | 未测；adb devices 无连接设备 |

## 最终产物

- `artifacts/ShadowReader-1.1.0-debug.apk`，28,284,835 字节。
- `artifacts/ShadowReader-1.1.0-debug.apk.sha256`。
- SHA-256：`02281b055d577c22306f4077e41ec190e137d4f17d9812f39529ad74cceeeb2e`。
- 调试签名延续旧版，可直接覆盖安装；实际手机覆盖安装与数据回归仍待验证。
- Whisper 模型没有内置 APK，由用户点击下载；APK 中的 DebugProbesKt.bin 是协程运行库资源。
- 使用说明：项目 README；手机清单：device-checklist.md。
- 升级前源码备份：`artifacts/source-before-v1.1.zip`。

## 自动覆盖范围

导入测试 8 项：网页去噪、正文选择、去重、分句、缩写 / 小数 / 引号、长度与导入校验。

反馈测试 10 项：大小写 / 标点 / 弯引号、明确缩写、词形替换、漏词 / 多词 / 替换、重复词对齐、最多三个重点、静音 / 短录音判定、三次有效差异提示、跨年 1 / 3 / 7 天与回次日、按文章和句号去重并使用最终结果。

播放测试 3 项：排除准备 / 暂停 / 重缓冲、按实际播放墙钟时间计算留白 / 关闭留白、速度上下限与 0.05 步长。它们验证计时与设置算法，不能替代真实 Media3 播放验收。

联网测试 1 项：生产 Kotlin 协议获取四种音色与三种内容变体，校验 MP3。默认离线构建会跳过此测试；本次通过 RUN_EDGE_INTEGRATION=1 实际运行。

迁移单独由 `scripts/verify-migration.py` 执行；这是主机 SQLite 和导出 schema 验证，不是手机 Room MigrationTestHelper 或覆盖安装测试。

## 尚未验证

未连接 Android 手机，因此以下项目不作通过声明：

- M4A 的 Android MediaCodec 解码、麦克风、JNI 模型加载 / 推理 / 取消的完整设备流程。
- 正确跟读、漏词、替换、多词、噪声、长停顿的实际 ASR 效果；当前七个合成样句只是后续验收素材。
- 实时调速与保持音高、留白计时、四种音色听感、缓存离线回放、后台 / 音频中断及页面切换。
- 识别速度、峰值内存、低内存设备表现、温升、耗电、复习全流程和 UI 布局。
- 真实覆盖安装的数据与录音保留。

尝试另做桌面 ASR 检验：本地 GCC 8.1 与上游 C++ filesystem 不兼容；官方 b5130 Windows 二进制下载出现 TLS 握手失败。没有修改上游源码或用其他识别器替代，所以桌面 ASR 结果仍未验证。离线词对齐测试通过不等于 ASR 效果通过。

上游在非 Git 源码目录查询版本元信息时有提示，路径带空格的特征探测也有提示；三种 ABI 编译成功。32 位 ARM 保留上游的一条格式化输出编译警告，没有修改第三方代码。

最终构建日志：`artifacts/v1.1-build-final.log`；APK 签名：`artifacts/v1.1-apk-signature.log`；机器可读测试和打包摘要：`artifacts/v1.1-validation.json`。按手机清单完成验收后再补充实际结果。
