import SwiftUI

@MainActor struct SettingsView: View {
    @ObservedObject var controller: ReaderController
    @ObservedObject private var whisper: WhisperModel
    @Environment(\.dismiss) private var dismiss
    init(controller: ReaderController) { self.controller = controller; self._whisper = ObservedObject(wrappedValue: controller.whisper) }
    var body: some View {
        NavigationStack { Form {
            Section("训练模式") {
                Picker("模式", selection: $controller.mode) { Text("离线发音反馈").tag(FeedbackMode.pronunciation); Text("离线内容对比").tag(FeedbackMode.content); Text("自由听读").tag(FeedbackMode.listening) }
                Toggle("内容一致后两秒下一句", isOn: $controller.autoNext)
                Text("自动下一句只适用于内容对比模式；发音模式始终手动推进。").font(.caption)
            }
            Section("原音") {
                Picker("音色", selection: $controller.voice) { ForEach(SpeechVoice.allCases) { Text($0.label).tag($0) } }
                Button("试听统一样句") { controller.previewVoice() }
                Text("Edge 音色联网生成文章句子，录音不会发送。完全离线时使用缓存原音或已安装的系统语音；失败后请手动切换音色。").font(.caption).foregroundStyle(.secondary)
            }
            Section("循环与留白") {
                Picker("循环次数", selection: $controller.repeats) { ForEach([1, 2, 3, 5], id: \.self) { Text("\($0)遍").tag($0) } }
                Toggle("自适应留白", isOn: $controller.gapEnabled)
                Picker("实际播放时长比例", selection: $controller.gapRatio) { ForEach([0.5, 1.0, 1.5, 2.0], id: \.self) { Text("\($0, specifier: "%.1f")倍").tag($0) } }
                Slider(value: $controller.gapBuffer, in: 0...5, step: 1); Text("额外缓冲 \(Int(controller.gapBuffer)) 秒")
            }
            Section("Whisper 离线识别") {
                Text(whisper.ready ? "英文模型已下载（约60MB）" : "未下载；仍可听读、录音和本地发音评估。")
                if whisper.downloading { ProgressView(value: whisper.progress); Button("取消下载") { whisper.cancel() } }
                else { Button(whisper.ready ? "重新下载 / 修复模型" : "下载并校验模型") { whisper.download() } }
                if !whisper.message.isEmpty { Text(whisper.message).font(.caption) }
            }
            Section("离线发音模型") { Text("与 Android alpha3 相同的 INT8、VAD、词表及英语 G2P。首次分析会检查资源，约378MB发音资产随构建准备。").font(.caption); Text("本客户端为实验版，iOS 编译与真机性能验证状态见交付记录。").font(.caption).foregroundStyle(.secondary) }
        }.navigationTitle("设置").toolbar { Button("关闭") { controller.stop(); dismiss() } } }
    }
}
@MainActor struct ReportView: View {
    let report: TrainingReport
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack { List {
            Section("本次训练") { Text("练习 \(report.rows.count) 句 · \(report.attempts) 次录音或重分析"); Text("内容一致 \(report.count(.consistent)) · 待复习 \(report.count(.different)) · 跳过 \(report.count(.skipped)) · 未评估 / 争议 \(report.count(.unevaluated)+report.count(.disputed))") }
            if report.rows.contains(where: { $0.final.mode == .pronunciation }) {
                Section("发音声学证据") {
                    Text("绿 \(report.pronunciationCounts[.green, default: 0]) · 黄 \(report.pronunciationCounts[.yellow, default: 0]) · 红 \(report.pronunciationCounts[.red, default: 0]) · 灰 \(report.pronunciationCounts[.unknown, default: 0])")
                    Text("按各句本次最终结果统计；未获得发音结果的句子也会保留在明细中。颜色不表示发音正确概率。").font(.caption)
                    let priorities = report.rows.flatMap { $0.final.pronunciation?.words ?? [] }.filter { $0.status == .red || $0.status == .yellow }.prefix(3)
                    ForEach(Array(priorities.enumerated()), id: \.offset) { _, word in Text("重练：\(word.text) · \(word.status.label)").foregroundStyle(word.status.color) }
                }
            }
            Section("逐句结果") { ForEach(report.rows) { row in VStack(alignment: .leading, spacing: 6) {
                Text(row.final.text)
                Text("\(row.final.mode == .pronunciation ? "发音训练" : "内容训练") · \(row.attemptCount)次尝试 · \(resultLabel(row.final))").font(.caption).foregroundStyle(.secondary)
                if let evidence = row.final.pronunciation { Text("可评估覆盖率 \(Int(evidence.coverage*100))%").font(.caption) }
            } } }
        }.navigationTitle("训练报告").toolbar { Button("完成") { dismiss() } } }
    }
    private func resultLabel(_ value: Attempt) -> String {
        if value.mode == .pronunciation && value.result != .skipped { return value.pronunciation == nil ? "发音未评估" : "已生成声学证据" }
        switch value.result { case .consistent: return "内容一致"; case .different: return "待复习"; case .skipped: return "跳过"; case .unevaluated: return "未评估"; case .disputed: return "识别争议" }
    }
}
