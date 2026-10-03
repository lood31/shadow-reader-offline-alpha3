import SwiftUI

@MainActor struct ReaderRootView: View {
    @ObservedObject var controller: ReaderController
    @Environment(\.scenePhase) private var scenePhase
    @State private var showImport = false
    @State private var showSettings = false
    @State private var shared: SharedImport?
    var body: some View {
        NavigationStack {
            Group {
                if controller.article != nil { TrainingView(controller: controller) }
                else {
                    List {
                        Section {
                            Button { controller.startReview() } label: { Label("到期复习 · \(controller.due.count) 句", systemImage: "calendar.badge.clock") }.disabled(controller.due.isEmpty)
                            Button { showImport = true } label: { Label("导入文章", systemImage: "plus.circle") }.disabled(controller.store == nil)
                            if controller.library.isEmpty {
                                Button("体验示例") {
                                    do { let article = try controller.saveImport(ArticleImporter.fromText(title: "Small steps", body: "Every day we can learn something new. Small steps make a big difference. Listen carefully and try again.")); controller.start(article) }
                                    catch { controller.notice = error.localizedDescription }
                                }.disabled(controller.store == nil)
                            }
                        }
                        Section("素材库") {
                            ForEach(controller.library) { article in
                                NavigationLink { ArticlePreviewView(article: article, controller: controller) } label: {
                                    VStack(alignment: .leading, spacing: 5) { Text(article.title).font(.headline); Text("\(article.sentences.count) 句 · 上次第 \(article.lastIndex+1) 句").font(.caption).foregroundStyle(.secondary) }
                                }
                                .swipeActions { Button("删除", role: .destructive) { controller.delete(article) } }
                            }
                        }
                        Section { Text("离线发音证据实验版。颜色反映声学证据，数字发音分数为空。").font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
            .navigationTitle(controller.article?.title ?? "影子阅读器")
            .toolbar {
                if controller.article != nil { ToolbarItem(placement: .topBarLeading) { Button("结束") { controller.endTraining() } } }
                ToolbarItem(placement: .topBarTrailing) { Button { controller.stop(); showSettings = true } label: { Image(systemName: "slider.horizontal.3") } }
            }
        }
        .sheet(isPresented: $showSettings) { SettingsView(controller: controller) }
        .sheet(isPresented: $showImport, onDismiss: { shared = nil }) { ImportView(controller: controller, shared: shared) }
        .sheet(isPresented: $controller.showReport) { ReportView(report: controller.report ?? TrainingReport(events: [])) }
        .alert("提示", isPresented: Binding(get: { controller.notice != nil }, set: { if !$0 { controller.notice = nil } })) { Button("关闭") { controller.notice = nil } } message: { Text(controller.notice ?? "") }
        .onAppear(perform: checkInbox)
        .onChange(of: scenePhase) { _, phase in if phase == .active { checkInbox() } }
    }
    private func checkInbox() {
        guard controller.article == nil, !showImport else { return }
        // App Groups is optional for main-app development signing. Import UI remains usable.
        if let item = try? ShareInbox.pending().first { shared = item; showImport = true }
    }
}
@MainActor struct ArticlePreviewView: View {
    var article: Article
    @ObservedObject var controller: ReaderController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            Text(article.title).font(.title2.bold()); Text("\(article.sentences.count) 句").foregroundStyle(.secondary)
            Button("开始 / 继续训练") { controller.start(article); dismiss() }.buttonStyle(.borderedProminent)
            Text(article.body).textSelection(.enabled)
        }.padding() }.navigationTitle("文章预览")
    }
}
@MainActor struct ImportView: View {
    @ObservedObject var controller: ReaderController
    var shared: SharedImport?
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var bodyText = ""
    @State private var link = ""
    @State private var preview: ImportedText?
    @State private var loading = false
    @State private var error = ""
    @State private var task: Task<Void, Never>?
    var body: some View {
        NavigationStack { Form {
            if let preview {
                Section("导入预览") { Text(preview.title).font(.headline); Text("\(SentenceSplitter.split(preview.body).count) 句"); Text(preview.body).textSelection(.enabled) }
                Button("保存到素材库") { do { _ = try controller.saveImport(preview); if let shared { try ShareInbox.remove(shared) }; dismiss() } catch { self.error = error.localizedDescription } }
                Button("返回编辑") { self.preview = nil }
            } else {
                Section("网页地址") { TextField("https://…", text: $link).textInputAutocapitalization(.never).keyboardType(.URL); Button("获取正文并预览") { fetch() }.disabled(loading || link.isEmpty) }
                Section("粘贴正文") { TextField("标题（可选）", text: $title); TextEditor(text: $bodyText).frame(minHeight: 180); Button("预览正文") { do { preview = try ArticleImporter.fromText(title: title, body: bodyText); error = "" } catch { self.error = error.localizedDescription } }.disabled(loading) }
                if let shared { Button("丢弃这条分享", role: .destructive) { do { try ShareInbox.remove(shared); dismiss() } catch { self.error = error.localizedDescription } } }
            }
            if loading { ProgressView("获取正文…"); Button("取消请求") { task?.cancel() } }
            if !error.isEmpty { Text(error).foregroundStyle(.red) }
        }.navigationTitle("导入文章").toolbar { Button("关闭") { task?.cancel(); dismiss() } } }
        .onAppear { title = shared?.title ?? ""; bodyText = shared?.text ?? ""; link = shared?.url ?? "" }
        .onDisappear { task?.cancel() }
    }
    private func fetch() {
        loading = true; error = ""
        task = Task {
            defer { loading = false }
            do { preview = try await ArticleImporter().fromURL(link) }
            catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
}
@MainActor struct TrainingView: View {
    @ObservedObject var controller: ReaderController
    @ObservedObject private var audio: AudioService
    @State private var detail: WordAssessment?
    init(controller: ReaderController) { self.controller = controller; self._audio = ObservedObject(wrappedValue: controller.audio) }
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) {
            Text("\(controller.reviewing ? "到期复习" : "跟读训练") · 第 \(controller.position+1)/\(controller.article?.sentences.count ?? 0) 句").font(.subheadline).foregroundStyle(.secondary)
            Text(highlightedSentence).font(.title2).textSelection(.enabled)
            HStack { Text(String(format: "%.2f×", controller.speed)); Slider(value: $controller.speed, in: 0.5...2, step: 0.05); Button("−") { controller.speed = PlaybackSettings.speed(controller.speed-0.05) }; Button("＋") { controller.speed = PlaybackSettings.speed(controller.speed+0.05) } }
            HStack { ForEach([0.75, 0.9, 1.0, 1.25], id: \.self) { value in Button(String(format: "%.2f×", value)) { controller.speed = value }.buttonStyle(.bordered) } }
            HStack {
                Button("听原句", systemImage: "play.fill") { controller.playOriginal() }.disabled(audio.recording)
                if controller.busy && (audio.playing || controller.gapRemaining > 0 || controller.paused) { Button(controller.paused ? "继续" : "暂停") { controller.pauseOrResume() } }
                if controller.busy { Button("停止") { controller.stop() } }
            }.buttonStyle(.bordered)
            if controller.gapRemaining > 0 { HStack { Text(String(format: "留白 %.1f 秒", controller.gapRemaining)).monospacedDigit(); Button("跳过留白") { controller.skipGap() } } }
            Button(audio.recording ? "结束录音并分析" : "开始录音", systemImage: audio.recording ? "stop.circle.fill" : "mic.fill") { controller.toggleRecording() }.buttonStyle(.borderedProminent).tint(audio.recording ? .red : .accentColor).disabled(controller.status == "准备录音…" && controller.busy)
            if audio.recording { Button("重新录音") { controller.stop(); controller.toggleRecording() } }
            if controller.busy && !audio.playing && controller.gapRemaining == 0 { ProgressView() }
            Text(controller.status).font(.callout).foregroundStyle(.secondary)
            if let latest = controller.latest {
                if let evidence = latest.pronunciation {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("逐词声学证据 · \(Int(evidence.coverage*100))% 音素可评估").font(.headline)
                        Text("绿：目标支持较强 · 红：竞争较强 · 黄：分歧 · 灰：未评估").font(.caption).foregroundStyle(.secondary)
                        WordGrid(words: evidence.words) { detail = $0 }
                        Text("重练重点："+evidence.words.filter { $0.status == .red || $0.status == .yellow }.prefix(3).map(\.text).joined(separator: "、")).font(.callout)
                        Button("导出实验数据") { controller.exportCurrent() }.disabled(controller.busy)
                    }.padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
                if !latest.recognized.isEmpty {
                    Text("识别到的内容").font(.headline); Text(latest.recognized).textSelection(.enabled)
                    ForEach(Array(latest.differences.filter { $0.kind != .match }.prefix(3).enumerated()), id: \.offset) { _, difference in Text(differenceLabel(difference)).foregroundStyle(.orange) }
                    Text("内容一致只说明转录相符，不表示发音已经标准。").font(.caption).foregroundStyle(.secondary)
                    Button("识别有误") { controller.dispute() }.disabled(controller.busy)
                }
                if latest.recordingName != nil {
                    HStack { Button("听自己的录音") { controller.playRecording() }; Button("重新分析") { controller.reanalyze() } }.disabled(controller.busy || audio.recording)
                }
            }
            if let export = controller.exportURL { ShareLink(item: export) { Label("分享 / 保存实验 ZIP", systemImage: "square.and.arrow.up") } }
            if controller.consecutiveDifferences >= 3 { Text("已连续三次识别出差异，可以加入复习，也可以继续练习。").foregroundStyle(.orange) }
            HStack { Button("标为难句") { controller.markDifficult() }; Spacer(); Button("暂时跳过") { controller.skip() } }.disabled(controller.busy || audio.recording)
            HStack { Button("上一句") { controller.previous() }.disabled(controller.reviewing || controller.position == 0); Spacer(); Button("下一句") { controller.advance() } }
        }.padding() }
        .sheet(item: $detail) { word in PhonemeDetailView(word: word, controller: controller) }
    }
    private func differenceLabel(_ value: WordDifference) -> String {
        switch value.kind { case .match: return value.expected ?? ""; case .missing: return "漏词：\(value.expected ?? "")"; case .extra: return "多词：\(value.heard ?? "")"; case .substitute: return "替换：\(value.expected ?? "") → \(value.heard ?? "")" }
    }
    private var highlightedSentence: AttributedString {
        var result = AttributedString(controller.text)
        for word in controller.latest?.pronunciation?.words ?? [] {
            if let sourceRange = word.range(in: controller.text), let range = Range(sourceRange, in: result) {
                result[range].foregroundColor = word.status.color
                result[range].backgroundColor = word.status.color.opacity(0.12)
            }
        }
        return result
    }
}
extension PronunciationStatus {
    var color: Color { switch self { case .green: return .green; case .yellow: return .orange; case .red: return .red; case .unknown: return .gray } }
    var label: String { switch self { case .green: return "目标支持较强"; case .yellow: return "证据有分歧"; case .red: return "竞争证据较强"; case .unknown: return "未评估" } }
}
@MainActor struct WordGrid: View {
    var words: [WordAssessment]; var select: (WordAssessment) -> Void
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 84))], alignment: .leading) {
            ForEach(words) { word in Button { select(word) } label: { Text(word.text).frame(maxWidth: .infinity).padding(8).background(word.status.color.opacity(0.14), in: RoundedRectangle(cornerRadius: 8)).foregroundStyle(word.status.color) }.accessibilityLabel("\(word.text)，\(word.status.label)") }
        }
    }
}
@MainActor struct PhonemeDetailView: View {
    var word: WordAssessment
    @ObservedObject var controller: ReaderController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack { List {
            Section { Text(word.status.label).foregroundStyle(word.status.color); Button("听标准单词") { controller.speakText(word.text) }
                if word.startMs != nil && word.endMs != nil { Button("听自己的单词片段（估计位置）") { controller.playRecording(startMs: word.startMs, endMs: word.endMs) } }
            }
            Section("音素证据") {
                ForEach(word.phonemes) { phone in VStack(alignment: .leading, spacing: 7) {
                    HStack { Text("/\(phone.ipa)/").font(.title3); Text(phone.status.label).foregroundStyle(phone.status.color) }
                    Text(reasonLabel(phone.reasonCode)).font(.caption)
                    if phone.startMs != nil && phone.endMs != nil { Button("回放此音素（估计位置）") { controller.playRecording(startMs: phone.startMs, endMs: phone.endMs) } }
                    DisclosureGroup("开发详情") { Text("可靠性：\(phone.confidence)\n帧竞争：\(phone.frameMargin.map(String.init(describing:)) ?? "未评估")\n路径竞争：\(phone.pathMargin.map(String.init(describing:)) ?? "未评估")\n竞争音素：\(phone.competitorIpa ?? "无")").font(.caption).textSelection(.enabled) }
                } }
            }
        }.navigationTitle(word.text).toolbar { Button("关闭") { controller.stop(); dismiss() } } }
        .onDisappear { controller.stop() }
    }
    private func reasonLabel(_ reason: String?) -> String {
        switch reason { case "TARGET_SUPPORTED": return "目标音素的声学支持较强。"; case "COMPETING_EVIDENCE": return "其他音素有更强的竞争证据，建议重练。"; case "POSSIBLE_ALLOPHONE": return "可能是美式闪音，证据暂不判红。"; case "LOW_RELIABILITY": return "声学可靠性不足。"; case "ALIGNMENT_AMBIGUOUS": return "对齐位置不唯一，无法可靠评估。"; case "TARGET_INCOMPATIBLE": return "整句与目标不兼容。"; case "MIXED_EVIDENCE": return "帧与路径证据存在分歧。"; default: return "无法可靠评估。" }
    }
}
