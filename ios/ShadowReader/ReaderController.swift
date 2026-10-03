import Foundation
import SwiftUI

private enum SavedSettings {
    static func double(_ key: String, fallback: Double) -> Double {
        (UserDefaults.standard.object(forKey: key) as? Double) ?? fallback
    }
    static func integer(_ key: String, fallback: Int) -> Int {
        (UserDefaults.standard.object(forKey: key) as? Int) ?? fallback
    }
    static func bool(_ key: String, fallback: Bool) -> Bool {
        (UserDefaults.standard.object(forKey: key) as? Bool) ?? fallback
    }
}

@MainActor final class ReaderController: ObservableObject {
    @Published var library = [Article]()
    @Published var due = [ReviewItem]()
    @Published var article: Article?
    @Published var position = 0
    @Published var latest: Attempt?
    @Published var busy = false
    @Published var status = ""
    @Published var notice: String?
    @Published var gapRemaining = 0.0
    @Published var paused = false
    @Published var report: TrainingReport?
    @Published var showReport = false
    @Published var exportURL: URL?
    @Published var consecutiveDifferences = 0
    @Published var speed = SavedSettings.double("speed", fallback: 0.9) {
        didSet { UserDefaults.standard.set(speed, forKey: "speed"); audio.setSpeed(speed) }
    }
    @Published var voice = SpeechVoice(rawValue: UserDefaults.standard.string(forKey: "voice") ?? "") ?? .aria {
        didSet { stop(); UserDefaults.standard.set(voice.rawValue, forKey: "voice") }
    }
    @Published var mode = FeedbackMode(rawValue: UserDefaults.standard.string(forKey: "mode") ?? "") ?? .pronunciation {
        didSet { stop(); UserDefaults.standard.set(mode.rawValue, forKey: "mode") }
    }
    @Published var repeats = SavedSettings.integer("repeats", fallback: 1) { didSet { UserDefaults.standard.set(repeats, forKey: "repeats") } }
    @Published var gapEnabled = SavedSettings.bool("gapEnabled", fallback: true) { didSet { UserDefaults.standard.set(gapEnabled, forKey: "gapEnabled"); if !gapEnabled { skipGap() } } }
    @Published var gapRatio = SavedSettings.double("gapRatio", fallback: 1) { didSet { UserDefaults.standard.set(gapRatio, forKey: "gapRatio") } }
    @Published var gapBuffer = SavedSettings.double("gapBuffer", fallback: 1) { didSet { UserDefaults.standard.set(gapBuffer, forKey: "gapBuffer") } }
    @Published var autoNext = SavedSettings.bool("autoNext", fallback: true) { didSet { UserDefaults.standard.set(autoNext, forKey: "autoNext"); if !autoNext { advanceTask?.cancel() } } }
    let audio = AudioService(), speech = SpeechService(), whisper = WhisperModel(), inference = LocalInference()
    private(set) var store: TrainingStore?
    private var session = "", reviewQueue = [ReviewItem](), reviewCursor = 0
    private var version: UInt64 = 0
    private var work: Task<Void, Never>?, prefetch: Task<Void, Never>?, advanceTask: Task<Void, Never>?, recordLimit: Task<Void, Never>?
    private var recordingAttempt: Attempt?
    private var gapSkipped = false
    var text: String { guard let article, article.sentences.indices.contains(position) else { return "" }; return article.sentences[position] }
    var reviewing: Bool { !reviewQueue.isEmpty }
    init() {
        do { try AppFiles.prepare(); store = try TrainingStore(url: AppFiles.root.appendingPathComponent("reader.sqlite")); refresh() }
        catch { notice = error.localizedDescription }
        audio.onInterruption = { [weak self] in self?.stop(); self?.status = "音频已中断，录音与分析结果已保留。" }
    }
    func refresh() {
        do { library = try store?.articles() ?? []; due = try store?.due() ?? [] } catch { notice = error.localizedDescription }
    }
    func saveImport(_ imported: ImportedText) throws -> Article {
        guard let store else { throw ReaderError.message("数据库不可用，请重新启动应用。") }
        let sentences = SentenceSplitter.split(imported.body)
        guard !sentences.isEmpty else { throw ReaderError.message("没有可练习的句子。") }
        let article = Article(title: imported.title, body: imported.body, source: imported.source, sentences: sentences)
        try store.save(article: article); refresh(); return article
    }
    func start(_ value: Article) {
        stop(); finishPreviousSession(); reviewQueue = []; reviewCursor = 0
        do { session = UUID().uuidString; try store?.beginSession(id: session, review: false); article = value; select(min(max(0, value.lastIndex), value.sentences.count-1)) }
        catch { notice = error.localizedDescription }
    }
    func startReview() {
        refresh(); guard !due.isEmpty else { notice = "今天没有到期复习。"; return }
        stop(); finishPreviousSession(); reviewQueue = due; reviewCursor = 0
        do { session = UUID().uuidString; try store?.beginSession(id: session, review: true); article = reviewQueue[0].article; select(reviewQueue[0].position) }
        catch { notice = error.localizedDescription }
    }
    private func select(_ index: Int) {
        position = index; latest = nil; consecutiveDifferences = 0; status = ""; exportURL = nil
        do { if let article { latest = try store?.latestRecording(article: article.id, position: index) }; persistProgress() }
        catch { notice = error.localizedDescription }
    }
    private func persistProgress() {
        guard var article else { return }; article.lastIndex = position; self.article = article
        do { try store?.save(article: article) } catch { notice = error.localizedDescription }
    }
    func stop() {
        version &+= 1; work?.cancel(); work = nil; prefetch?.cancel(); prefetch = nil; advanceTask?.cancel(); advanceTask = nil; recordLimit?.cancel(); recordLimit = nil
        speech.cancelAll(); audio.stop(); paused = false; gapRemaining = 0; gapSkipped = false; busy = false
        if audio.recording, let snapshot = recordingAttempt {
            _ = audio.finishRecording()
            do { try store?.save(attempt: snapshot); latest = snapshot } catch { notice = error.localizedDescription }
        }
        recordingAttempt = nil
    }
    func background() { stop(); status = "已暂停，录音已保留。"; persistProgress(); whisper.cancel() }
    func playOriginal() {
        guard !text.isEmpty else { return }; stop(); let token = version, sentence = text, selectedVoice = voice
        status = "准备原音…"; busy = true
        work = Task {
            defer { if token == version { busy = false; speech.activeFile = nil } }
            do {
                let file = try await speech.sentenceFile(text: sentence, voice: selectedVoice)
                try Task.checkCancellation(); guard token == version else { return }; speech.activeFile = file
                if let article, article.sentences.indices.contains(position+1) {
                    let next = article.sentences[position+1]
                    prefetch = Task { _ = try? await speech.sentenceFile(text: next, voice: selectedVoice) }
                }
                for iteration in 0..<repeats {
                    status = "听原句 · \(iteration+1)/\(repeats)"
                    let played = try await audio.play(file, speed: speed)
                    gapSkipped = false; gapRemaining = PlaybackSettings.gap(played: played, ratio: gapRatio, buffer: gapBuffer, enabled: gapEnabled)
                    status = "留白跟读"
                    var last = ProcessInfo.processInfo.systemUptime
                    while gapRemaining > 0 && !gapSkipped {
                        try await Task.sleep(for: .milliseconds(50)); let now = ProcessInfo.processInfo.systemUptime
                        if !paused { gapRemaining = max(0, gapRemaining-(now-last)) }; last = now
                    }
                    gapRemaining = 0; try Task.checkCancellation()
                }
                status = mode == .listening ? "听读完成，可继续下一句。" : "点击录音，开始跟读。"
            } catch is CancellationError {} catch { if token == version { status = "原音生成或播放失败；请重试或手动选择系统语音。"; notice = error.localizedDescription } }
        }
    }
    func pauseOrResume() {
        paused.toggle(); if paused { audio.pause() } else { audio.resume(speed: speed) }
    }
    func skipGap() { gapSkipped = true; gapRemaining = 0; if paused { paused = false } }
    func previewVoice() { speakText("The quick brown fox jumps over the lazy dog.") }
    func speakText(_ value: String) {
        stop(); let token = version; busy = true
        work = Task {
            defer { if token == version { busy = false; speech.activeFile = nil } }
            do { let file = try await speech.sentenceFile(text: value, voice: voice); try Task.checkCancellation(); guard token == version else { return }; speech.activeFile = file; _ = try await audio.play(file, speed: speed) }
            catch is CancellationError {} catch { if token == version { notice = error.localizedDescription } }
        }
    }
    func toggleRecording() {
        if audio.recording { finishAndAnalyze(); return }
        guard let article else { return }; stop()
        let token = version
        var snapshot = Attempt(sessionId: session, articleId: article.id, position: position, text: text)
        snapshot.mode = mode; snapshot.recordingName = snapshot.id+".m4a"
        busy = true; status = "准备录音…"
        work = Task {
            do {
                try await audio.startRecording(at: AppFiles.recording(snapshot.recordingName!))
                try Task.checkCancellation(); guard token == version else { return }
                try store?.save(attempt: snapshot); recordingAttempt = snapshot; latest = snapshot; busy = false; status = "正在录音，再点一次结束（最多两分钟）。"
                recordLimit = Task { try? await Task.sleep(for: .seconds(120)); if !Task.isCancelled && token == version && audio.recording { finishAndAnalyze() } }
            } catch { if token == version { _ = audio.finishRecording(); busy = false; notice = error.localizedDescription } }
        }
    }
    private func finishAndAnalyze() {
        recordLimit?.cancel(); recordLimit = nil
        guard let snapshot = recordingAttempt else { _ = audio.finishRecording(); return }
        _ = audio.finishRecording(); recordingAttempt = nil; analyze(snapshot)
        if var value = article, value.id == snapshot.articleId {
            value.completedCount = max(value.completedCount, snapshot.position+1); article = value; persistProgress()
        }
    }
    func reanalyze() {
        guard let old = latest, old.recordingName != nil, let article else { return }
        stop(); var snapshot = Attempt(sessionId: session, articleId: article.id, position: position, text: text)
        snapshot.recordingName = old.recordingName; snapshot.mode = mode
        do { try store?.save(attempt: snapshot); analyze(snapshot) } catch { notice = error.localizedDescription }
    }
    private func analyze(_ snapshot: Attempt) {
        let token = version; busy = true; latest = snapshot; status = "正在处理录音…"
        work = Task {
            var result = snapshot
            defer { if token == version { busy = false } }
            do {
                guard let name = snapshot.recordingName else { throw ReaderError.message("录音未保存，未评估。") }
                let samples = try await inference.decode(AppFiles.recording(name))
                try Task.checkCancellation()
                guard TrainingRules.evaluable(samples) else { throw ReaderError.message("录音静音或过短，未评估；录音仍保留。") }
                if snapshot.mode == .listening { status = "录音已保存，本次未评估。"; return }
                if snapshot.mode == .pronunciation {
                    status = "正在本机分析发音，首次加载较慢…"
                    do {
                        result.pronunciation = try await inference.assess(PronunciationRequest(requestId: snapshot.id, samples: samples, expectedText: snapshot.text))
                        try Task.checkCancellation(); guard current(snapshot, token: token) else { return }
                        try store?.save(attempt: result); latest = result
                    } catch is CancellationError { throw CancellationError() }
                    catch { try Task.checkCancellation(); if current(snapshot, token: token) { status = "发音未评估：\(error.localizedDescription)" } }
                }
                guard whisper.ready else {
                    status = snapshot.mode == .pronunciation ? (result.pronunciation != nil ? "发音反馈已完成；Whisper 未下载，内容对比未评估。" : "发音未评估；Whisper 未下载，录音已保留。") : "录音已保存，请先下载 Whisper 模型。"
                    return
                }
                status = "正在本机进行内容对比…"
                let recognized = try await inference.recognize(samples: samples, requestId: snapshot.id)
                try Task.checkCancellation(); guard current(snapshot, token: token) else { return }
                guard !WordAligner.normalize(recognized).isEmpty else { throw ReaderError.message("没有识别到有效英语内容，未评估。") }
                let feedback = try WordAligner.compare(snapshot.text, recognized)
                result.recognized = recognized; result.differences = feedback.words; result.errorRatio = feedback.errorRatio
                result.result = feedback.consistent ? .consistent : .different
                if snapshot.mode == .content, let store {
                    result.reviewBefore = try store.reviews().first { $0.articleId == snapshot.articleId && $0.position == snapshot.position }
                    result.reviewApplied = true
                    try store.transaction { try store.save(attempt: result); try store.updateReview(for: result, inReview: reviewing) }
                    consecutiveDifferences = feedback.consistent ? 0 : consecutiveDifferences+1
                } else { try store?.save(attempt: result) }
                latest = result; refresh()
                status = snapshot.mode == .pronunciation ? "辅助内容对比已完成；不决定发音或掌握状态。" : (feedback.consistent ? "识别内容一致；不表示发音已经标准。" : "识别到的差异，按重点再读一次。")
                if snapshot.mode == .content && feedback.consistent && autoNext {
                    advanceTask = Task { try? await Task.sleep(for: .seconds(2)); if !Task.isCancelled && current(snapshot, token: token) && autoNext { advance() } }
                }
            } catch is CancellationError {}
            catch {
                if current(snapshot, token: token) {
                    result.result = .unevaluated; latest = result; do { try store?.save(attempt: result) } catch { notice = error.localizedDescription }
                    status = error.localizedDescription
                }
            }
        }
    }
    private func current(_ snapshot: Attempt, token: UInt64) -> Bool { token == version && article?.id == snapshot.articleId && position == snapshot.position && session == snapshot.sessionId && latest?.id == snapshot.id }
    func playRecording(startMs: Int? = nil, endMs: Int? = nil) {
        guard let snapshot = latest, let name = snapshot.recordingName, !audio.recording else { return }; stop(); let token = version
        work = Task {
            do {
                let url = try AppFiles.recording(name)
                if startMs != nil, let evidence = snapshot.pronunciation {
                    let samples = try await inference.decode(url)
                    guard try FileDigest.sha256(PcmWav.encode(samples)) == evidence.audioSha256 else { throw ReaderError.message("录音已变化，请重新评估。") }
                }
                try Task.checkCancellation(); guard token == version else { return }
                audio.playClip(url, startMs: startMs.map { max(0, $0-80) }, endMs: endMs.map { $0+80 })
            } catch is CancellationError {} catch { if token == version { notice = error.localizedDescription } }
        }
    }
    func dispute() {
        guard var snapshot = latest, !busy else { return }; advanceTask?.cancel()
        do {
            snapshot.result = .disputed
            try store?.transaction {
                if snapshot.reviewApplied {
                    if let before = snapshot.reviewBefore { try store?.save(review: before) }
                    else { try store?.removeReview(article: snapshot.articleId, position: snapshot.position) }
                    snapshot.reviewApplied = false
                }
                try store?.save(attempt: snapshot)
            }
            latest = snapshot; consecutiveDifferences = max(0, consecutiveDifferences-1); refresh(); status = "已标记识别有误；可重识别或继续，本次不自动通过。"
        } catch { notice = error.localizedDescription }
    }
    func markDifficult() {
        guard let article else { return }
        do {
            var review = try store?.reviews().first { $0.articleId == article.id && $0.position == position } ?? SentenceReview(articleId: article.id, position: position)
            review.difficult = true; if review.due == nil { review.due = TrainingRules.nextReview(today: Date(), round: 0, passed: false).1 }
            try store?.save(review: review); refresh(); status = "已标为难句，加入复习。"
        } catch { notice = error.localizedDescription }
    }
    func skip() {
        guard let article else { return }; stop()
        var event = Attempt(sessionId: session, articleId: article.id, position: position, text: text); event.result = .skipped; event.mode = mode
        do { try store?.save(attempt: event); advance() } catch { notice = error.localizedDescription }
    }
    func previous() { guard !reviewing, position > 0 else { return }; stop(); select(position-1) }
    func advance() {
        stop()
        if reviewing {
            reviewCursor += 1
            if reviewCursor >= reviewQueue.count { endTraining(); return }
            article = reviewQueue[reviewCursor].article; select(reviewQueue[reviewCursor].position)
        } else if let article {
            if position+1 >= article.sentences.count { endTraining() } else { select(position+1) }
        }
    }
    private func finishPreviousSession() { if !session.isEmpty { do { try store?.endSession(session) } catch { notice = error.localizedDescription } }; session = "" }
    func endTraining() {
        stop()
        do { report = TrainingReport(events: try store?.attempts(session: session) ?? []); try store?.endSession(session); showReport = true }
        catch { notice = error.localizedDescription }
        article = nil; reviewQueue = []; session = ""; refresh()
    }
    func delete(_ value: Article) {
        do { try store?.deleteArticle(value.id); refresh() } catch { notice = error.localizedDescription }
    }
    func exportCurrent() {
        guard let snapshot = latest, let name = snapshot.recordingName, let evidence = snapshot.pronunciation, !busy else { return }
        stop(); let token = version; busy = true
        work = Task {
            defer { if token == version { busy = false } }
            do {
                let samples = try await inference.decode(AppFiles.recording(name)); try Task.checkCancellation()
                let wav = try PcmWav.encode(samples)
                guard FileDigest.sha256(wav) == evidence.audioSha256 else { throw ReaderError.message("录音已变化，请重新评估后导出。") }
                let url = try ExperimentExporter.export(wav: wav, evidence: evidence)
                guard token == version else { return }; exportURL = url; status = "实验数据已准备，可选择分享或保存到文件。"
            } catch is CancellationError {} catch { if token == version { notice = error.localizedDescription } }
        }
    }
}
