import Foundation

/// Independent Swift port of acoustic-evidence-v1. No pronunciation probability or score.
final class EvidenceScorer {
    struct Path { var score: Double; var start: Int; var end: Int; var ambiguous: Bool }
    struct Phone {
        var status: PronunciationStatus; var confidence: Double; var reason: String
        var gop: Double?; var frameMargin: Double?; var pathMargin: Double?; var competitor: Int?
        var startFrame: Int?; var endFrame: Int?
        static func unknown(_ reason: String, start: Int? = nil, end: Int? = nil) -> Phone {
            Phone(status: .unknown, confidence: 0, reason: reason, gop: nil, frameMargin: nil, pathMargin: nil, competitor: nil, startFrame: start, endFrame: end)
        }
    }
    let logp: [[Double]], targets: [[Int]], blank: Int, phoneIds: [Int]
    let tCount: Int, sCount: Int
    var a: [[Double]], h: [[Double]], back: [[Int]], skips: [Bool]
    private let checkCancelled: () throws -> Void
    private func emission(_ t: Int, _ s: Int) -> Double { s % 2 == 0 ? logp[t][blank] : targets[(s-1)/2].map { logp[t][$0] }.max()! }
    private func disjoint(_ x: [Int], _ y: [Int]) -> Bool { !x.contains { y.contains($0) } }
    init(logp: [[Double]], targets: [[Int]], blank: Int, phoneIds: [Int], checkCancelled: @escaping () throws -> Void = {}) throws {
        guard let row = logp.first, !row.isEmpty, !targets.isEmpty, targets.count <= 400,
              row.indices.contains(blank), logp.allSatisfy({ $0.count == row.count && $0.allSatisfy(\.isFinite) }),
              targets.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0 != blank && row.indices.contains($0) } }),
              !phoneIds.isEmpty, phoneIds.allSatisfy({ row.indices.contains($0) }) else { throw ReaderError.message("ALIGNMENT_FAILED") }
        self.logp = logp; self.targets = targets; self.blank = blank; self.phoneIds = phoneIds
        self.checkCancelled = checkCancelled; tCount = logp.count; sCount = targets.count * 2 + 1
        a = Array(repeating: Array(repeating: -.infinity, count: sCount), count: tCount)
        h = a; back = Array(repeating: Array(repeating: 0, count: sCount), count: tCount)
        skips = Array(repeating: false, count: sCount)
        if targets.count > 1 { for i in 1..<targets.count { skips[2*i+1] = disjoint(targets[i], targets[i-1]) } }
        a[0][0] = emission(0, 0); a[0][1] = emission(0, 1)
        if tCount > 1 { for t in 1..<tCount {
            try checkCancelled()
            for s in 0..<sCount {
                var best = a[t-1][s], step = 0
                if s > 0 && a[t-1][s-1] > best { best = a[t-1][s-1]; step = 1 }
                if s > 1 && skips[s] && a[t-1][s-2] > best { best = a[t-1][s-2]; step = 2 }
                a[t][s] = best + emission(t, s); back[t][s] = step
            }
        } }
        h[tCount-1][sCount-1] = 0; h[tCount-1][sCount-2] = 0
        if tCount > 1 { for t in stride(from: tCount-2, through: 0, by: -1) {
            try checkCancelled()
            for s in 0..<sCount {
                var best = emission(t+1, s) + h[t+1][s]
                if s+1 < sCount { best = max(best, emission(t+1, s+1)+h[t+1][s+1]) }
                if s+2 < sCount && skips[s+2] { best = max(best, emission(t+1, s+2)+h[t+1][s+2]) }
                h[t][s] = best
            }
        } }
    }
    var score: Double { max(a[tCount-1][sCount-1], a[tCount-1][sCount-2]) }
    func replace(_ i: Int, allowed: [Int]) throws -> Path {
        let c = 2*i+1
        let enterSkip = i > 0 && disjoint(allowed, targets[i-1])
        let exitSkip = i+1 < targets.count && disjoint(allowed, targets[i+1])
        var prev = -Double.infinity, minStart = 0, maxStart = 0
        var best = -Double.infinity, bestMin = 0, bestMax = 0, endMin = 0, endMax = 0
        for t in 0..<tCount {
            if t % 128 == 0 { try checkCancelled() }
            var choices = [(prev, minStart, maxStart)]
            if t > 0 {
                choices.append((a[t-1][c-1], t, t))
                if enterSkip { choices.append((a[t-1][c-2], t, t)) }
            } else if c == 1 { choices.append((0, 0, 0)) }
            let value = choices.map { $0.0 }.max()!
            prev = value + allowed.map { logp[t][$0] }.max()!
            if !value.isFinite { continue }
            let winners = choices.filter { abs($0.0-value) <= 1e-9 }
            minStart = winners.map { $0.1 }.min()!; maxStart = winners.map { $0.2 }.max()!
            var tail = t+1 == tCount ? (c == sCount-2 ? 0 : -Double.infinity) : emission(t+1, c+1)+h[t+1][c+1]
            if t+1 < tCount && exitSkip { tail = max(tail, emission(t+1, c+2)+h[t+1][c+2]) }
            let total = prev + tail
            if !total.isFinite { continue }
            if total > best+1e-9 {
                best = total; bestMin = minStart; bestMax = maxStart; endMin = t+1; endMax = t+1
            } else if abs(total-best) <= 1e-9 {
                best = max(best, total); bestMin = min(bestMin, minStart); bestMax = max(bestMax, maxStart)
                endMin = min(endMin, t+1); endMax = max(endMax, t+1)
            }
        }
        return Path(score: best, start: bestMin, end: endMin, ambiguous: bestMin != bestMax || endMin != endMax)
    }
    func assess(ipas: [String], byId: [Int: String]) throws -> [Phone] {
        guard score.isFinite, ipas.count == targets.count else { throw ReaderError.message("ALIGNMENT_FAILED") }
        var occurrences = Array(repeating: [Int](), count: targets.count)
        var s = a[tCount-1][sCount-1] >= a[tCount-1][sCount-2] ? sCount-1 : sCount-2
        for t in stride(from: tCount-1, through: 0, by: -1) {
            if s % 2 == 1 { occurrences[(s-1)/2].append(t) }; s -= back[t][s]
        }
        guard occurrences.allSatisfy({ !$0.isEmpty }) else { throw ReaderError.message("ALIGNMENT_FAILED") }
        let frames = occurrences.map { Array($0.reversed()) }
        let centers = frames.map { ($0[($0.count-1)/2]+$0[$0.count/2])/2 }
        var cuts = [frames.first!.first!]
        if centers.count > 1 { for i in 1..<centers.count { cuts.append((centers[i-1]+centers[i]+1)/2) } }
        cuts.append(frames.last!.last!+1)
        let gap = (logp.reduce(0) { $0 + $1.max()! } - score) / Double(tCount)
        return try targets.indices.map { i in
            try checkCancelled()
            let lo = min(cuts[i], frames[i].first!), hi = max(cuts[i+1], frames[i].last!+1)
            if gap > 0.50 { return Phone.unknown("TARGET_INCOMPATIBLE") }
            let competitors = phoneIds.filter { !targets[i].contains($0) }
            func support(_ id: Int) -> Double { frames[i].reduce(0) { $0 + exp(logp[$1][id]) } / Double(frames[i].count) }
            let other = competitors.sorted { x, y in let sx = support(x), sy = support(y); return sx == sy ? x < y : sx > sy }.prefix(3)
            let original = try replace(i, allowed: targets[i])
            var best: (Path, Int)?
            for id in other { let candidate = try replace(i, allowed: [id]); if best == nil || candidate.score > best!.0.score { best = (candidate, id) } }
            guard let best else { return Phone.unknown("ALIGNMENT_FAILED", start: lo, end: hi) }
            let candidate = best.0
            let union = Set(frames[i] + Array(candidate.start..<max(candidate.start, candidate.end))).sorted()
            guard !union.isEmpty else { return Phone.unknown("ALIGNMENT_FAILED", start: lo, end: hi) }
            var reliability = 0.0, margin = 0.0
            for t in union {
                let probs = phoneIds.map { exp(logp[t][$0]) }, mass = probs.reduce(0, +)
                let entropy = -probs.reduce(0) { acc, p in let q = p/max(mass, 1e-12); return acc+q*log(max(q, 1e-12)) } / log(Double(max(2, phoneIds.count)))
                reliability += mass*(1-entropy)
                let targetMass = targets[i].reduce(0) { $0+exp(logp[t][$1]) }
                margin += log(min(1, max(1e-12, targetMass))) - log(max(1e-12, competitors.map { exp(logp[t][$0]) }.max() ?? 0))
            }
            let confidence = min(1, max(0, reliability/Double(union.count)))
            margin /= Double(union.count)
            let pathMargin = (score-candidate.score)/Double(union.count)
            let raw = frames[i].reduce(0) { acc, t in acc+log(min(1, max(1e-12, targets[i].reduce(0) { $0+exp(logp[t][$1]) }))) } / Double(frames[i].count)
            let ambiguous = original.ambiguous || candidate.ambiguous || candidate.start < lo || candidate.end > hi || !candidate.score.isFinite
            let (status, reason) = Self.classify(confidence: confidence, frame: margin, path: pathMargin, ambiguous: ambiguous, flap: ["t", "d"].contains(ipas[i]) && byId[best.1] == "ɾ")
            return Phone(status: status, confidence: confidence, reason: reason, gop: raw, frameMargin: margin, pathMargin: pathMargin, competitor: best.1, startFrame: lo, endFrame: hi)
        }
    }
    static func classify(confidence: Double, frame: Double, path: Double, ambiguous: Bool = false, flap: Bool = false) -> (PronunciationStatus, String) {
        if ambiguous { return (.unknown, "ALIGNMENT_AMBIGUOUS") }
        if confidence < 0.70 { return (.unknown, "LOW_RELIABILITY") }
        if frame >= log(3) && path >= log(3) { return (.green, "TARGET_SUPPORTED") }
        if confidence >= 0.80 && frame <= -log(10) && path <= -log(10) { return flap ? (.yellow, "POSSIBLE_ALLOPHONE") : (.red, "COMPETING_EVIDENCE") }
        return (.yellow, "MIXED_EVIDENCE")
    }
    static func summarize(_ states: [PronunciationStatus]) -> (PronunciationStatus, Double) {
        let coverage = Double(states.filter { $0 != .unknown }.count)/Double(max(1, states.count))
        if states.contains(.red) { return (.red, coverage) }
        if states.isEmpty || coverage < 0.8 { return (.unknown, coverage) }
        return (states.allSatisfy { $0 == .green } ? .green : .yellow, coverage)
    }
}
