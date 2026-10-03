import Foundation

struct AssetManifest: Decodable {
    struct Spec: Decodable { var bytes: Int; var sha256: String }
    var bundleVersion: String; var modelVersion: String; var onnxruntime: String
    var g2pVersion: String; var evidenceVersion: String; var validationReportSha256: String
    var files: [String: Spec]
}
/// DispatchQueue ownership spans physical native cleanup, including cancelled requests.
struct TokenVocabulary {
    private var ids = [Data: Int]()
    var byId = [Int: String]()
    var count: Int { byId.count }
    subscript(_ token: String) -> Int? { ids[Data(token.utf8)] }
    var phoneIds: [Int] { byId.filter { !$0.value.hasPrefix("<") && !["|", " "].contains($0.value) }.keys.sorted() }
    init() {}
    init(data: Data) throws {
        guard let object = try JSONSerialization.jsonObject(with: data) as? NSDictionary else { throw ReaderError.message("模型词表无效。") }
        // NSString preserves literal Unicode keys; Swift String equality would
        // merge u + combining tilde (191) with precomposed u-tilde (374).
        for (key, value) in object {
            guard let token = key as? String, let number = value as? NSNumber else { throw ReaderError.message("模型词表无效。") }
            let id = number.intValue
            guard byId[id] == nil else { throw ReaderError.message("模型词表无效。") }
            ids[Data(token.utf8)] = id; byId[id] = token
        }
        guard Set(byId.keys) == Set(0..<count), self["<pad>"] != nil else { throw ReaderError.message("模型词表无效。") }
    }
}
final class LocalInference: PronunciationEngine, OfflineRecognizer, @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.shadowreader.ios.inference", qos: .userInitiated)
    private let native = SRNativeEngine()
    private var manifest: AssetManifest?
    private var directory: URL?
    private var vocab = TokenVocabulary()
    private var whisperVerified = false
    func assess(_ request: PronunciationRequest) async throws -> PronunciationAssessment {
        try await work(id: request.requestId) { flag in try self.calculate(request, flag: flag) }
    }
    func recognize(samples: [Float], requestId: String) async throws -> String {
        try await work(id: requestId) { flag in
            guard TrainingRules.evaluable(samples) else { throw ReaderError.message("录音静音或过短，未评估。") }
            guard (try? WhisperModel.file.resourceValues(forKeys: [.fileSizeKey]).fileSize) == WhisperModel.size else { throw ReaderError.message("请先下载离线识别模型；录音已保留。") }
            if !self.whisperVerified {
                guard try FileDigest.sha256(file: WhisperModel.file, check: flag.check) == WhisperModel.sha256 else { throw ReaderError.message("Whisper 模型校验失败，请重新下载。") }
                self.whisperVerified = true
            }
            // Give Whisper exclusive memory ownership; next pronunciation request reloads lazily.
            self.native.releasePronunciation()
            let text = try self.native.transcribe(PcmWav.floatData(samples), model: WhisperModel.file.path).trimmingCharacters(in: .whitespacesAndNewlines)
            try flag.check()
            guard !text.isEmpty else { throw ReaderError.message("没有识别到有效英语内容，未评估。") }; return text
        }
    }
    func decode(_ url: URL) async throws -> [Float] {
        try await work(id: UUID().uuidString) { flag in try PcmDecoder.decode(url, check: flag.check) }
    }
    func phonemesForTesting(_ text: String) async throws -> [String] {
        try await work(id: UUID().uuidString) { flag in _ = try self.ready(flag); return try self.phones(text) }
    }
    private func work<T>(id: String, operation: @escaping (CancellationFlag) throws -> T) async throws -> T {
        let flag = CancellationFlag()
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    self.native.beginRequest(id)
                    defer { self.native.endRequest(id) }
                    do { try flag.check(); let result = try operation(flag); try flag.check(); continuation.resume(returning: result) }
                    catch {
                        let original = error
                        do { try flag.check(); continuation.resume(throwing: original) }
                        catch { continuation.resume(throwing: CancellationError()) }
                    }
                }
            }
        }, onCancel: { flag.cancel(); self.native.cancelRequest(id) })
    }
    private func ready(_ flag: CancellationFlag) throws -> Double {
        let start = ProcessInfo.processInfo.systemUptime
        if directory == nil {
            guard let root = Bundle.main.url(forResource: "pronunciation", withExtension: nil) else { throw ReaderError.message("缺少离线发音资源，请运行资源准备脚本后重新构建。") }
            let receipt = try JSONDecoder().decode(AssetManifest.self, from: Data(contentsOf: root.appendingPathComponent("manifest.json")))
            guard receipt.onnxruntime == "1.24.3", receipt.g2pVersion == "espeak-ng-1.52.0", receipt.evidenceVersion == "acoustic-evidence-v1" else { throw ReaderError.message("离线资源版本不匹配。") }
            for (name, spec) in receipt.files {
                try flag.check(); let file = root.appendingPathComponent(name).standardizedFileURL
                guard file.path.hasPrefix(root.standardizedFileURL.path+"/"), try file.resourceValues(forKeys: [.fileSizeKey]).fileSize == spec.bytes,
                      try FileDigest.sha256(file: file, check: flag.check) == spec.sha256 else { throw ReaderError.message("离线资源校验失败：\(name)，录音已保留。") }
            }
            let inventory = try TokenVocabulary(data: Data(contentsOf: root.appendingPathComponent("vocab.json")))
            manifest = receipt; vocab = inventory; directory = root
        }
        try flag.check(); try native.loadPronunciation(at: directory!.path)
        return (ProcessInfo.processInfo.systemUptime-start)*1000
    }
    private func phones(_ text: String) throws -> [String] {
        let raw = try native.phonemize(text).replacingOccurrences(of: "ˈ", with: "").replacingOccurrences(of: "ˌ", with: "")
        return raw.components(separatedBy: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "_"))).filter { !$0.isEmpty }
    }
    private func calculate(_ request: PronunciationRequest, flag: CancellationFlag) throws -> PronunciationAssessment {
        let load = try ready(flag), start = ProcessInfo.processInfo.systemUptime
        let wav = try PcmWav.encode(request.samples)
        let samples = try PcmWav.quantized(request.samples).map { Float($0)/32768 }
        let regex = try NSRegularExpression(pattern: "[A-Za-z0-9]+(?:['’‘][A-Za-z]+)*")
        let matches = regex.matches(in: request.expectedText, range: NSRange(request.expectedText.startIndex..., in: request.expectedText))
        guard !matches.isEmpty, matches.count <= 120 else { throw ReaderError.message("发音评估支持不超过120词的英文句子，录音已保留。") }
        let weak = ["a": [["ɐ", "ə", "eɪ"]], "the": [["ð"], ["ə", "iː", "ɪ"]], "to": [["t"], ["ə", "uː"]], "of": [["ə", "ʌ"], ["v"]]]
        var words = [WordAssessment](), targets = [[Int]](), ipas = [String]()
        for (index, match) in matches.enumerated() {
            try flag.check()
            let value = (request.expectedText as NSString).substring(with: match.range)
            let lower = value.lowercased(with: Locale(identifier: "en_US_POSIX")).replacingOccurrences(of: "’", with: "'").replacingOccurrences(of: "‘", with: "'")
            let phones = try self.phones(lower)
            guard !phones.isEmpty, phones.allSatisfy({ vocab[$0] != nil }) else { throw ReaderError.message("目标音素不在模型词表内，未评估；录音已保留。") }
            let alternatives = weak[lower].flatMap { $0.count == phones.count ? $0 : nil }
            for (j, phone) in phones.enumerated() {
                let values = ([phone]+(alternatives?[j] ?? [])).compactMap { vocab[$0] }
                var seen = Set<Int>(); targets.append(values.filter { seen.insert($0).inserted }); ipas.append(phone)
            }
            words.append(WordAssessment(wordIndex: index, text: value, sourceStart: match.range.location, sourceEnd: NSMaxRange(match.range), phonemes: phones.enumerated().map { PhonemeAssessment(phonemeIndex: $0.offset, ipa: $0.element) }))
        }
        guard targets.count <= 400 else { throw ReaderError.message("目标句超过400个音素，请练习较短句子；录音已保留。") }
        var assessment = PronunciationAssessment(requestId: request.requestId, text: request.expectedText, audioSha256: FileDigest.sha256(wav), modelVersion: manifest!.modelVersion, words: words)
        assessment.timingsMs["initialization"] = load
        let rms = sqrt(samples.reduce(0.0) { $0+Double($1)*Double($1) }/Double(samples.count))
        var reason: String? = rms < 0.003 ? "NO_SPEECH" : (Double(samples.filter { abs($0) >= 0.999 }.count) > Double(samples.count)*0.02 ? "CLIPPED_AUDIO" : nil)
        let speech: [NSNumber] = reason == nil ? try native.speechRange(PcmWav.floatData(samples)) : []
        if speech.isEmpty && reason == nil { reason = "NO_SPEECH" }
        assessment.timingsMs["preprocessing"] = (ProcessInfo.processInfo.systemUptime-start)*1000
        if reason == nil && speech.count == 2 {
            let offset = max(0, speech[0].intValue-1600), end = min(samples.count, speech[1].intValue+1600)
            guard end > offset else { throw ReaderError.message("VAD 区间无效。") }
            let segment = Array(samples[offset..<end])
            let mean = Float(segment.reduce(0.0) { $0+Double($1) }/Double(segment.count))
            let variance = Float(segment.reduce(0.0) { let difference = Double($1-mean); return $0+difference*difference }/Double(segment.count))
            let values = segment.map { ($0-mean)/sqrt(variance+1e-7) }
            let inferenceStart = ProcessInfo.processInfo.systemUptime
            let logits = try native.logits(PcmWav.floatData(values)).map { row -> [Double] in
                let values = row.map(\.doubleValue)
                guard values.count == vocab.count, values.allSatisfy(\.isFinite), let maximum = values.max() else { throw ReaderError.message("模型输出异常。") }
                let normalizer = log(values.reduce(0) { $0+exp($1-maximum) })+maximum
                return values.map { $0-normalizer }
            }
            assessment.timingsMs["inference"] = (ProcessInfo.processInfo.systemUptime-inferenceStart)*1000
            let scoringStart = ProcessInfo.processInfo.systemUptime
            let ids = vocab.phoneIds
            let byId = vocab.byId
            var scored: [EvidenceScorer.Phone]
            do { scored = try EvidenceScorer(logp: logits, targets: targets, blank: vocab["<pad>"]!, phoneIds: ids, checkCancelled: flag.check).assess(ipas: ipas, byId: byId) }
            catch ReaderError.message("ALIGNMENT_FAILED") { reason = "ALIGNMENT_FAILED"; scored = targets.map { _ in .unknown("ALIGNMENT_FAILED") } }
            var occurrence = 0
            func time(_ frame: Int) -> Int { Int((Double(offset)+Double(frame)/Double(logits.count)*Double(segment.count))/16+0.5) }
            for i in words.indices {
                for j in words[i].phonemes.indices {
                    let result = scored[occurrence]; occurrence += 1
                    words[i].phonemes[j].status = result.status; words[i].phonemes[j].confidence = result.confidence
                    words[i].phonemes[j].reasonCode = result.reason; words[i].phonemes[j].gopRaw = result.gop
                    words[i].phonemes[j].frameMargin = result.frameMargin; words[i].phonemes[j].pathMargin = result.pathMargin
                    words[i].phonemes[j].competitorIpa = result.competitor.flatMap { byId[$0] }
                    words[i].phonemes[j].startMs = result.startFrame.map(time); words[i].phonemes[j].endMs = result.endFrame.map(time)
                }
                let summary = EvidenceScorer.summarize(words[i].phonemes.map(\.status))
                words[i].status = summary.0; words[i].coverage = summary.1
                words[i].confidence = words[i].phonemes.map(\.confidence).min() ?? 0
                words[i].startMs = words[i].phonemes.first?.startMs; words[i].endMs = words[i].phonemes.last?.endMs
            }
            if scored.allSatisfy({ $0.reason == "TARGET_INCOMPATIBLE" }) { reason = "TARGET_INCOMPATIBLE" }
            assessment.timingsMs["scoring"] = (ProcessInfo.processInfo.systemUptime-scoringStart)*1000
        }
        let allPhones = words.flatMap(\.phonemes)
        assessment.words = words; assessment.coverage = Double(allPhones.filter { $0.status != .unknown }.count)/Double(max(1, allPhones.count)); assessment.reasonCode = reason
        assessment.timingsMs["total"] = (ProcessInfo.processInfo.systemUptime-start)*1000
        assessment.diagnostics = ["platform": "iOS", "physicalRamBytes": String(ProcessInfo.processInfo.physicalMemory), "physicalFootprintBytesAtCompletion": String(SRNativeEngine.physicalFootprint()), "memoryMetric": "physical_footprint_snapshot_not_peak", "bundleVersion": manifest!.bundleVersion, "modelSha256": manifest!.files["model.int8.onnx"]!.sha256]
        try flag.check(); try assessment.validate(); return assessment
    }
}
