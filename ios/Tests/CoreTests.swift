import XCTest
@testable import ShadowReader

final class CoreTests: XCTestCase {
    func testWordsNormalizationAndMorphology() throws {
        XCTAssertTrue(try WordAligner.compare("I CAN’T go, today!", "I can not go today.").consistent)
        XCTAssertTrue(try WordAligner.compare("I'm ready and we'll go", "I am ready and we will go").consistent)
        XCTAssertFalse(try WordAligner.compare("She's ready", "She has ready").consistent)
        let difference = try WordAligner.compare("We begin today", "we beginning today").differences
        XCTAssertEqual(difference, [WordDifference(kind: .substitute, expected: "begin", heard: "beginning")])
    }
    func testMissingExtraRepeatedAndEmptyWords() throws {
        XCTAssertEqual(try WordAligner.compare("I read a book", "I read book").differences.first?.kind, .missing)
        XCTAssertEqual(try WordAligner.compare("I read a book", "I read a good book").differences.first?.kind, .extra)
        let repeated = try WordAligner.compare("I had had a plan", "I had a plan")
        XCTAssertEqual(repeated.words.filter { $0.kind == .match }.count, 4); XCTAssertEqual(repeated.differences.count, 1)
        XCTAssertEqual(try WordAligner.compare("", "hello").differences.first?.kind, .extra)
        XCTAssertEqual(try WordAligner.compare("a", "a b c d").priorities.count, 3)
    }
    func testReviewUsesLocalCalendarDaysAcrossYearAndDST() throws {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "America/New_York")!
        let date = calendar.date(from: DateComponents(year: 2026, month: 12, day: 31, hour: 23))!
        let first = TrainingRules.nextReview(today: date, round: 0, passed: true, calendar: calendar)
        XCTAssertEqual(first.0, 1); XCTAssertEqual(calendar.dateComponents([.year, .month, .day], from: first.1!), DateComponents(year: 2027, month: 1, day: 1))
        let march = calendar.date(from: DateComponents(year: 2026, month: 3, day: 7, hour: 23))!
        XCTAssertEqual(calendar.component(.day, from: TrainingRules.nextReview(today: march, round: 1, passed: true, calendar: calendar).1!), 10)
        XCTAssertNil(TrainingRules.nextReview(today: date, round: 3, passed: true).1)
        XCTAssertEqual(TrainingRules.nextReview(today: date, round: 3, passed: false).0, 0)
    }
    func testReportsUseFinalPerSentenceAndExcludeSkippedAttempts() {
        var first = Attempt(sessionId: "s", articleId: "a", position: 0, text: "one"); first.mode = .content; first.result = .different
        var passed = first; passed.id = "second"; passed.result = .consistent
        var skipped = first; skipped.position = 1; skipped.result = .skipped
        let report = TrainingReport(events: [first, passed, skipped])
        XCTAssertEqual(report.rows.count, 2); XCTAssertEqual(report.attempts, 2); XCTAssertEqual(report.rows[0].attemptCount, 2)
        XCTAssertEqual(report.count(.consistent), 1); XCTAssertEqual(report.count(.different), 0)
        first.mode = .pronunciation; first.result = .consistent
        XCTAssertEqual(TrainingReport(events: [first]).count(.consistent), 0)
    }
    func testSilentShortAndInvalidAudioStayUnevaluated() {
        XCTAssertFalse(TrainingRules.evaluable(Array(repeating: 0, count: 16000)))
        XCTAssertFalse(TrainingRules.evaluable(Array(repeating: 0.3, count: 7999)))
        XCTAssertFalse(TrainingRules.evaluable(Array(repeating: 0.001, count: 16000)))
        XCTAssertFalse(TrainingRules.evaluable(Array(repeating: Float.nan, count: 16000)))
        XCTAssertTrue(TrainingRules.evaluable((0..<16000).map { $0 % 2 == 0 ? 0.1 : -0.1 }))
        XCTAssertThrowsError(try PcmWav.encode(Array(repeating: 0.3, count: 480001)))
    }
    func testPCM16MatchesAndroidRoundingAndRIFFHeader() throws {
        var samples = Array(repeating: Float(0), count: 8000); samples[0] = -0.5/32768; samples[1] = 0.5/32768; samples[2] = 1; samples[3] = -1
        let values = try PcmWav.quantized(samples)
        XCTAssertEqual(Array(values.prefix(4)), [0, 1, 32767, -32768])
        let data = try PcmWav.encode(samples)
        XCTAssertEqual(data.count, 16044); XCTAssertEqual(String(data: data.prefix(4), encoding: .ascii), "RIFF")
        XCTAssertEqual(Array(data[44..<52]), [0, 0, 1, 0, 255, 127, 0, 128])
    }
    func testUTF16OffsetsIncludingEmoji() {
        let text = "😀 hello world"
        let word = WordAssessment(wordIndex: 0, text: "hello", sourceStart: 3, sourceEnd: 8, phonemes: [])
        XCTAssertEqual(word.range(in: text).map { String(text[$0]) }, "hello")
        let invalid = WordAssessment(wordIndex: 0, text: "bad", sourceStart: 1, sourceEnd: 2, phonemes: [])
        XCTAssertNil(invalid.range(in: text))
    }
    func testTimingExcludesPauseAndPreparationAndGapUsesPlayedTime() {
        var now = 0.0; let clock = PlaybackTiming(now: { now })
        now = 5; clock.playing(true); now = 7; clock.playing(false); now = 10; clock.playing(true); now = 11
        XCTAssertEqual(clock.finish(), 3)
        XCTAssertEqual(PlaybackSettings.gap(played: 3, ratio: 1.5, buffer: 1, enabled: true), 5.5)
        XCTAssertEqual(PlaybackSettings.gap(played: 3, ratio: 1, buffer: 1, enabled: false), 0)
        XCTAssertEqual(PlaybackSettings.speed(2.3), 2); XCTAssertEqual(PlaybackSettings.speed(0.1), 0.5)
    }
    func testSplitQuotesDecimalsAbbreviationsAndLongInput() {
        XCTAssertEqual(SentenceSplitter.split("Hello world. How are you? I am fine!\n\nA new paragraph."), ["Hello world.", "How are you?", "I am fine!", "A new paragraph."])
        XCTAssertEqual(SentenceSplitter.split("She said, \"Hello.\" Then she left."), ["She said, \"Hello.\"", "Then she left."])
        XCTAssertEqual(SentenceSplitter.split("Dr. Smith lives in the U.S. today. His score is 3.14. Try again."), ["Dr. Smith lives in the U.S. today.", "His score is 3.14.", "Try again."])
        let text = String(repeating: "word ", count: 1600).trimmingCharacters(in: .whitespaces)
        let chunks = SentenceSplitter.split(text); XCTAssertEqual(chunks.joined(separator: " "), text); XCTAssertTrue(chunks.allSatisfy { $0.utf16.count <= 3000 })
        XCTAssertEqual(SentenceSplitter.split(" \n\n "), [])
    }
    func testHTMLRemovesNavigationAdsAndNestedDuplicates() throws {
        let value = try ArticleImporter.extract(html: "<html><head><title>Fallback</title><meta property='og:title' content='A useful story'></head><body><nav>Home</nav><article><p>Every day we can learn something new by listening to the people around us.</p><blockquote><p>Small steps make a big difference when we practise every day.</p></blockquote><div class='ads'>Buy this now.</div><script>tracking()</script></article></body></html>", source: "https://example.com")
        XCTAssertEqual(value.title, "A useful story"); XCTAssertFalse(value.body.contains("Buy this")); XCTAssertFalse(value.body.contains("Home"))
        XCTAssertEqual(value.body.components(separatedBy: "Small steps").count-1, 1)
        XCTAssertThrowsError(try ArticleImporter.fromText(title: "", body: String(repeating: "a", count: 200001)))
    }
    func testEvidenceScoresRemainExplicitNullAndRangesAreValidated() throws {
        let word = WordAssessment(wordIndex: 0, text: "hello", sourceStart: 0, sourceEnd: 5, phonemes: [PhonemeAssessment(phonemeIndex: 0, ipa: "h")])
        var evidence = PronunciationAssessment(requestId: "request", text: "hello", audioSha256: "hash", modelVersion: "model", words: [word])
        let root = try JSONSerialization.jsonObject(with: EvidenceJSON.data(evidence)) as! [String: Any]
        XCTAssertTrue(root["accuracyScore"] is NSNull); XCTAssertTrue((root["words"] as! [[String: Any]])[0]["score"] is NSNull)
        evidence.words[0].score = 90; XCTAssertThrowsError(try evidence.validate())
    }
    func testCancellationFlagAcrossPhysicalWorker() throws {
        let flag = CancellationFlag(), started = DispatchSemaphore(value: 0), released = DispatchSemaphore(value: 0)
        let completed = expectation(description: "Worker acknowledges cancellation after cleanup")
        DispatchQueue(label: "test.native.worker").async {
            started.signal(); released.wait()
            XCTAssertThrowsError(try flag.check()); completed.fulfill()
        }
        XCTAssertEqual(started.wait(timeout: .now()+1), .success); flag.cancel(); released.signal(); wait(for: [completed], timeout: 2)
    }
}
