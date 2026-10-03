import Foundation

enum ReaderError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

struct Article: Codable, Identifiable, Hashable {
    var id = UUID().uuidString
    var title: String
    var body: String
    var source: String?
    var sentences: [String]
    var createdAt = Date()
    var lastIndex = 0
    var completedCount = 0
}
enum AttemptResult: String, Codable { case consistent = "CONSISTENT", different = "DIFFERENT", skipped = "SKIPPED", unevaluated = "UNEVALUATED", disputed = "DISPUTED" }
enum FeedbackMode: String, Codable, CaseIterable { case pronunciation = "PRONUNCIATION", content = "CONTENT", listening = "LISTENING" }
struct Attempt: Codable, Identifiable {
    var id = UUID().uuidString
    var sessionId: String
    var articleId: String
    var position: Int
    var text: String
    var createdAt = Date()
    var result = AttemptResult.unevaluated
    var recognized = ""
    var differences = [WordDifference]()
    var errorRatio = 0.0
    var mode = FeedbackMode.pronunciation
    var recordingName: String?
    var pronunciation: PronunciationAssessment?
    var reviewBefore: SentenceReview? = nil
    var reviewApplied = false
}
struct SentenceReview: Codable, Identifiable {
    var articleId: String
    var position: Int
    var difficult = false
    var streak = 0
    var round = 0
    var due: Date?
    var id: String { "\(articleId):\(position)" }
}
struct ReviewItem: Identifiable {
    var article: Article
    var position: Int
    var id: String { "\(article.id):\(position)" }
}
enum PronunciationStatus: String, Codable { case green = "GREEN", yellow = "YELLOW", red = "RED", unknown = "UNKNOWN" }
struct PhonemeAssessment: Codable, Identifiable {
    var phonemeIndex: Int
    var ipa: String
    var score: Double? = nil
    var confidence = 0.0
    var status = PronunciationStatus.unknown
    var startMs: Int? = nil
    var endMs: Int? = nil
    var reasonCode: String? = nil
    var gopRaw: Double? = nil
    var frameMargin: Double? = nil
    var pathMargin: Double? = nil
    var competitorIpa: String? = nil
    var id: Int { phonemeIndex }
}
struct WordAssessment: Codable, Identifiable {
    var wordIndex: Int
    var text: String
    var sourceStart: Int
    var sourceEnd: Int
    var score: Double? = nil
    var confidence = 0.0
    var status = PronunciationStatus.unknown
    var coverage = 0.0
    var startMs: Int? = nil
    var endMs: Int? = nil
    var phonemes: [PhonemeAssessment]
    var id: Int { wordIndex }
    func range(in text: String) -> Range<String.Index>? {
        guard sourceStart >= 0, sourceEnd >= sourceStart, sourceEnd <= text.utf16.count else { return nil }
        let units = text.utf16
        guard let start = String.Index(units.index(units.startIndex, offsetBy: sourceStart), within: text),
              let end = String.Index(units.index(units.startIndex, offsetBy: sourceEnd), within: text) else { return nil }
        return start..<end
    }
}
struct PronunciationAssessment: Codable {
    var schemaVersion = 2
    var assessmentKind = "ACOUSTIC_EVIDENCE_EXPERIMENTAL"
    var evidenceVersion = "acoustic-evidence-v1"
    var requestId: String
    var text: String
    var language = "en-US"
    var audioSha256: String
    var modelVersion: String
    var configVersion = "acoustic-evidence-v1"
    var calibrationStatus = "UNCALIBRATED"
    var accuracyScore: Double? = nil
    var engineId = "local-onnx-int8-ios"
    var timestampKind = "ESTIMATED_CTC"
    var coverage = 0.0
    var reasonCode: String? = nil
    var words: [WordAssessment]
    var timingsMs = [String: Double]()
    var diagnostics = [String: String]()
    func validate() throws {
        guard schemaVersion == 2, assessmentKind == "ACOUSTIC_EVIDENCE_EXPERIMENTAL", accuracyScore == nil,
              words.allSatisfy({ $0.score == nil && $0.range(in: text) != nil && $0.phonemes.allSatisfy { $0.score == nil } }) else {
            throw ReaderError.message("发音证据格式异常，未评估。")
        }
    }
}
struct PronunciationRequest { let requestId: String; let samples: [Float]; let expectedText: String }
protocol PronunciationEngine { func assess(_ request: PronunciationRequest) async throws -> PronunciationAssessment }
protocol OfflineRecognizer { func recognize(samples: [Float], requestId: String) async throws -> String }
protocol SpeechProvider { func sentenceFile(text: String, voice: SpeechVoice) async throws -> URL }

// JSON null is part of the existing v2 evidence contract, rather than an absent numeric score.
enum EvidenceJSON {
    static func data(_ assessment: PronunciationAssessment) throws -> Data {
        try assessment.validate()
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        var root = try JSONSerialization.jsonObject(with: encoder.encode(assessment)) as! [String: Any]
        root["accuracyScore"] = NSNull(); root["reasonCode"] = assessment.reasonCode.map { $0 as Any } ?? NSNull()
        root["words"] = try assessment.words.map { word -> [String: Any] in
            var obj = try JSONSerialization.jsonObject(with: encoder.encode(word)) as! [String: Any]
            obj["score"] = NSNull()
            obj["phonemes"] = try word.phonemes.map { phone -> [String: Any] in
                var p = try JSONSerialization.jsonObject(with: encoder.encode(phone)) as! [String: Any]
                p["score"] = NSNull(); return p
            }
            return obj
        }
        return try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys, .prettyPrinted])
    }
}
