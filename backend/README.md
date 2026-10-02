# Pronunciation backend · V2 alpha

Windows CPU 自托管试验版，支持 en-US。Android 上传目标文本和 16 kHz 单声道 PCM16 WAV；Whisper 转录不参与发音分数。

## 启动

本工作区已安装项目内 `.venv`、CPU PyTorch、eSpeak DLL 和固定修订模型，无需重新下载。PowerShell 在项目根目录运行：

```powershell
.\backend\start.ps1 -BindAddress 0.0.0.0
```

手机和电脑连接同一局域网，在 App 设置填写 `http://电脑的局域网IP:8765`，保存并启用“美式发音评估”。手机的 `localhost` 指向手机；Android 模拟器访问宿主用 `http://10.0.2.2:8765`。电脑访问 `http://127.0.0.1:8765/health` 应返回 `ready: true`。Windows 若弹出网络授权，按实际使用的私有网络选择；本项目未修改防火墙。

默认服务没有加载人工校准，返回音素定位、原始 GOP、可靠性，分数为 null、颜色为 UNKNOWN。试用公开语料的 **pilot 校准**：

```powershell
.\backend\start.ps1 -BindAddress 0.0.0.0 -Calibration config/pilot-speechocean.json
```

Pilot 尚未通过可靠评分验收，不能把颜色当作确定误读诊断。alpha2 增加声学冲突保护后，测试词覆盖率为 27.4%（原 alpha1 为 40.3%），7 个人工低分词均未能可靠评分；误读检出率和误绿率无法据此确认。发布前必须扩大人工数据与真机录音。

服务仅用于可信局域网；不带身份认证。debug APK 允许 HTTP，release APK 仅支持 HTTPS，应通过受控反向代理提供 TLS。后端只在请求内存中处理录音，不保存上传文件。开发 CLI/benchmark 输出为主动保存的本地测试数据。

## 从零准备

```powershell
.\backend\setup.ps1
```

本次实测 Windows x64 / Python 3.13.15 / torch 2.9.1+cpu / torchaudio 2.9.1+cpu；没有安装 CUDA。`requirements.txt` 固定直接依赖，`requirements.lock.txt` 记录本机完整环境。eSpeak 在项目虚拟环境内由 espeakng-loader 0.2.4 提供，未全局安装；Linux 需要自行提供系统 eSpeak NG。

主模型 [facebook/wav2vec2-lv-60-espeak-cv-ft](https://huggingface.co/facebook/wav2vec2-lv-60-espeak-cv-ft)，固定修订 `ae45363bf3413b374fecd9dc8bc1df0e24c3b7f4`，权重约 1.26 GB；下载后生成 manifest，启动核验权重、配置、词表 SHA-256。模型权重遵循 Apache-2.0；phonemizer / eSpeak NG 分别保留上游 GPL 许可，loader 的 MIT 许可不替代内含 eSpeak 的许可。模型与 G2P 均运行在后端，没有装入 APK。

## API

`POST /api/v1/pronunciation/assess`，multipart 字段：

- `metadata`：JSON 字符串，`{"requestId":"唯一尝试ID","text":"The government announced a new policy.","language":"en-US"}`。
- `audio`：PCM16 WAV，16 kHz、mono，0.5–30 秒，最多 1 MiB。

响应含 schemaVersion、请求 ID、原文、音频 SHA-256、模型/评分/配置/校准版本、逐词 UTF-16 原文位置、音素 IPA、估计时间戳、GOP、可靠性和状态。Android 校验 ID/文本/音频哈希，防止旧请求结果错配。所有未实现维度与可靠性不足的分数使用 null；detected/likely 不伪造。

校准缺失：`UNCALIBRATED`；试验校准：`PILOT`。`confidenceKind=HEURISTIC` 表示熵与非 blank 质量构造的可靠性指标，不是经验证的正确率概率；`timestampKind=ESTIMATED_CTC` 表示估计边界。

错误含 `error.code/message`：400 元数据、413 过大、422 音频或目标不支持、429 单请求推理繁忙、503 模型未准备、500 推理失败。客户端不自动重试上传。取消后丢弃响应，CPU forward 完成前仍保持串行锁。

## 评分与诊断

G2P 保留原词出现位置，用 eSpeak en-US 音素及短功能词允许变体。Silero VAD 只裁头尾，保留句内停顿。log-space CTC Viterbi 按出现顺序对齐，重复音素必须经 blank；对齐失败不均匀分配时间。高路径损失返回 TARGET_INCOMPATIBLE。

Raw GOP = 对齐到音素非 blank 状态的帧上 `mean(log(sum(P(允许的目标音素))))`。独立可靠性由完整非特殊音素分布的质量与归一熵计算，不用目标概率充当 confidence。人工单调回归将 raw 映射到 0–100；词分为可靠音素的 confidence 加权均值减严重低分惩罚，P10 可靠性与覆盖率不足时 UNKNOWN。词准确度覆盖率不足时句准确度 null；overall/completeness/fluency/prosody 本阶段均 null。

阈值集中于 `config/thresholds.json`，80/55/0.70 均为待验的试验配置，UI 只解释后端状态。默认不启用未经验证的校准。

alpha2 配置版本 `experimental-v2-conflict-guard`：在相同对齐帧上，将允许目标音素的概率之和与最强其他非 blank 音素概率比较，输出平均 log 比率 `targetCompetitorLogRatio`。可靠性足够但比率小于 0 时返回 `ACOUSTIC_CONFLICT`，音素与整词分数均 null/UNKNOWN；可靠性不足返回 `LOW_RELIABILITY`。零是等似然边界，不是按用户录音拟合的阈值。此保护用于阻止标量校准覆盖明显冲突，不将音素强制判红，不输出未经验证的替换诊断。raw GOP、预训练权重及 pilot 单调映射均未改变。界面用 0–1 的“模型可靠性指标”，不再用容易混淆为准确率的百分比。

```powershell
cd backend
.\.venv\Scripts\python.exe -m pronunciation.cli runs/aria.wav --text 'Every small step brings you closer to your goal.' --output runs/diagnostic.json
.\.venv\Scripts\python.exe -m pytest -q
```

诊断 JSON 提供每音素 GOP、supportFrames、logMeanPosterior、nonBlankMass、时间、可靠性、词分和各阶段耗时。开发工具显式保存输出，服务日志仅记录请求 ID、模型修订和耗时。

## 人工校准重现

[SpeechOcean762 / OpenSLR 101](https://www.openslr.org/101/) 由五位专家独立评分，普通话母语成人及儿童英语语料，CC BY 4.0。署名：Junbo Zhang et al., *speechocean762: An Open-Source Non-native English Speech Corpus For Pronunciation Assessment*, Interspeech 2021；SpeechOcean / 数据集作者。本地样本由官方 archive 选取，标签来自官方 GitHub 修订 `613968e3b0b789fc33936fb5eba1973176ba7d11`。

在官方 train 集内重新划分 12 位互斥说话人，6/3/3 位 train/dev/test，各 10 句，共 120 句；选择包含每位说话人的高、低评分录音。仅音素数和 ARPAbet↔IPA 允许映射都一致的整词用于音素校准，119 个词不兼容而排除；不把不同音素强行配对。保留所有兼容原始 GOP 标签，可靠性门槛仅在展示时应用。

本次已提取 120 条独立 WAV 和哈希 manifest；为节省下载，停止了原 archive 的剩余下载，本地 `.partial.tar.gz` 不是完整归档，不用于正式重现。完整重现需从 OpenSLR 下载原归档，已有 `data/speechocean762/scores.json` 与 `train-utt2spk`：

```powershell
cd backend
.\.venv\Scripts\python.exe -m pronunciation.benchmark --archive data/speechocean762.tar.gz
.\.venv\Scripts\python.exe -m pronunciation.calibrate runs/speechocean-pilot/labels.jsonl --model-version ae45363bf3413b374fecd9dc8bc1df0e24c3b7f4 --output runs/custom-calibration.json
```

`runs/speechocean-pilot` 保存样本、原始结果、标签、manifest、校准和测试报告。可分发的小型试验校准与报告快照位于 `config/pilot-speechocean.json` 和 `docs/pronunciation-v2-validation.md`。自采标签 JSONL 每行需 speaker/split/gopRaw/humanScore/modelVersion，禁止说话人跨集合。校准不代表验收完成。
