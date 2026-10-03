import Foundation

enum DifferenceKind: String, Codable { case match = "MATCH", missing = "MISSING", substitute = "SUBSTITUTE", extra = "EXTRA" }
struct WordDifference: Codable, Hashable { var kind: DifferenceKind; var expected: String?; var heard: String? }
struct Feedback {
    var words: [WordDifference]
    var differences: [WordDifference] { words.filter { $0.kind != .match } }
    var consistent: Bool { differences.isEmpty }
    var errorRatio: Double { Double(differences.count) / Double(max(1, words.filter { $0.expected != nil }.count)) }
    var priorities: [WordDifference] { var seen = Set<WordDifference>(); return Array(differences.filter { seen.insert($0).inserted }.prefix(3)) }
}
enum WordAligner {
    static let contractions = ["can't":"can not", "cannot":"can not", "won't":"will not", "don't":"do not", "doesn't":"does not", "didn't":"did not", "isn't":"is not", "aren't":"are not", "wasn't":"was not", "weren't":"were not", "haven't":"have not", "hasn't":"has not", "hadn't":"had not", "couldn't":"could not", "wouldn't":"would not", "shouldn't":"should not", "mustn't":"must not", "i'm":"i am", "you're":"you are", "we're":"we are", "they're":"they are", "i've":"i have", "you've":"you have", "we've":"we have", "they've":"they have", "i'll":"i will", "you'll":"you will", "he'll":"he will", "she'll":"she will", "we'll":"we will", "they'll":"they will"]
    static func normalize(_ text: String) -> [String] {
        let value = text.lowercased(with: Locale(identifier: "en_US_POSIX")).replacingOccurrences(of: "’", with: "'").replacingOccurrences(of: "‘", with: "'")
        let regex = try! NSRegularExpression(pattern: "[a-z0-9]+(?:'[a-z]+)?")
        return regex.matches(in: value, range: NSRange(value.startIndex..., in: value)).flatMap {
            let token = (value as NSString).substring(with: $0.range)
            return (contractions[token] ?? token).split(separator: " ").map(String.init)
        }
    }
    static func compare(_ expected: String, _ heard: String) throws -> Feedback {
        let a = normalize(expected), b = normalize(heard)
        guard a.count <= 1000, b.count <= 1000 else { throw ReaderError.message("句子过长，无法评估。") }
        var dp = Array(repeating: Array(repeating: 0, count: b.count + 1), count: a.count + 1)
        for i in 0...a.count { dp[i][0] = i }; for j in 0...b.count { dp[0][j] = j }
        if !a.isEmpty && !b.isEmpty {
            for i in 1...a.count { for j in 1...b.count {
                dp[i][j] = min(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1] + (a[i-1] == b[j-1] ? 0 : 1))
            } }
        }
        var i = a.count, j = b.count, words = [WordDifference]()
        while i > 0 || j > 0 {
            if i > 0 && j > 0 && dp[i][j] == dp[i-1][j-1] + (a[i-1] == b[j-1] ? 0 : 1) {
                words.append(WordDifference(kind: a[i-1] == b[j-1] ? .match : .substitute, expected: a[i-1], heard: b[j-1])); i -= 1; j -= 1
            } else if i > 0 && dp[i][j] == dp[i-1][j]+1 {
                words.append(WordDifference(kind: .missing, expected: a[i-1], heard: nil)); i -= 1
            } else { words.append(WordDifference(kind: .extra, expected: nil, heard: b[j-1])); j -= 1 }
        }
        return Feedback(words: words.reversed())
    }
}
enum TrainingRules {
    static func evaluable(_ samples: [Float]) -> Bool {
        guard samples.count >= 8000, samples.allSatisfy(\.isFinite) else { return false }
        let rms = sqrt(samples.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(samples.count))
        return rms >= 0.003 && samples.filter { abs($0) >= 0.01 }.count >= 1600
    }
    static func nextReview(today: Date, round: Int, passed: Bool, calendar: Calendar = .current) -> (Int, Date?) {
        let day = calendar.startOfDay(for: today)
        if !passed { return (0, calendar.date(byAdding: .day, value: 1, to: day)) }
        guard round < 3 else { return (4, nil) }
        return (round + 1, calendar.date(byAdding: .day, value: [1, 3, 7][max(0, round)], to: day))
    }
}
struct ReportRow: Identifiable {
    var final: Attempt
    var attemptCount: Int
    var id: String { "\(final.articleId):\(final.position)" }
}
struct TrainingReport {
    var rows: [ReportRow]
    var attempts: Int
    init(events: [Attempt]) {
        var order = [String](), groups = [String: [Attempt]]()
        for event in events {
            let key = "\(event.articleId):\(event.position)"
            if groups[key] == nil { order.append(key) }; groups[key, default: []].append(event)
        }
        rows = order.compactMap { key in
            guard let group = groups[key], let last = group.last else { return nil }
            return ReportRow(final: last, attemptCount: group.filter { $0.result != .skipped }.count)
        }
        attempts = events.filter { $0.result != .skipped }.count
    }
    func count(_ result: AttemptResult) -> Int { rows.filter { $0.final.result == result && ($0.final.mode != .pronunciation || result == .skipped) }.count }
    var pronunciationCounts: [PronunciationStatus: Int] {
        var counts = [PronunciationStatus: Int]()
        for row in rows where row.final.mode == .pronunciation {
            for word in row.final.pronunciation?.words ?? [] { counts[word.status, default: 0] += 1 }
        }
        return counts
    }
}
enum PlaybackSettings {
    static func speed(_ value: Double) -> Double { min(2, max(0.5, (min(2, max(0.5, value))*20).rounded(.toNearestOrEven)/20)) }
    static func gap(played: Double, ratio: Double, buffer: Double, enabled: Bool) -> Double {
        enabled ? max(0, played) * min(2, max(0.5, ratio)) + min(5, max(0, buffer)) : 0
    }
}
final class PlaybackTiming {
    private let now: () -> Double
    private var started: Double?
    private var elapsed = 0.0
    init(now: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }) { self.now = now }
    func playing(_ active: Bool) {
        if active { if started == nil { started = now() } }
        else { if let started { elapsed += max(0, now() - started) }; started = nil }
    }
    func finish() -> Double { playing(false); return elapsed }
}
