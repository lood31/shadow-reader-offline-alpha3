import XCTest
@testable import ShadowReader

private struct Fixture: Decodable {
    struct Gold: Decodable { var status: PronunciationStatus; var reasonCode: String; var confidence: Double; var gopRaw: Double?; var pathMargin: Double? }
    struct Case: Decodable { var id: String; var logp: [[Double]]; var targets: [[Int]]; var ipas: [String]; var expected: [Gold] }
    var blank: Int; var phoneIds: [Int]; var vocab: [String: Int]; var cases: [Case]
}
final class EvidenceTests: XCTestCase {
    func testIndependentSwiftPortMatchesDesktopFixtures() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "evidence", withExtension: "json", subdirectory: "Fixtures"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        let byId = Dictionary(uniqueKeysWithValues: fixture.vocab.map { ($0.value, $0.key) })
        for example in fixture.cases {
            let actual = try EvidenceScorer(logp: example.logp, targets: example.targets, blank: fixture.blank, phoneIds: fixture.phoneIds).assess(ipas: example.ipas, byId: byId)
            XCTAssertEqual(actual.count, example.expected.count)
            for (value, gold) in zip(actual, example.expected) {
                XCTAssertEqual(value.status, gold.status, example.id); XCTAssertEqual(value.reason, gold.reasonCode, example.id)
                XCTAssertEqual(value.confidence, gold.confidence, accuracy: 2e-6, example.id)
                if let raw = gold.gopRaw { XCTAssertEqual(try XCTUnwrap(value.gop), raw, accuracy: 2e-6, example.id) }
                if let margin = gold.pathMargin { XCTAssertEqual(try XCTUnwrap(value.pathMargin), margin, accuracy: 1e-7, example.id) }
            }
        }
    }
    func testColorThresholdsAmbiguityAndAllophones() throws {
        XCTAssertEqual(EvidenceScorer.classify(confidence: 0.7, frame: log(3), path: log(3)).0, .green)
        XCTAssertEqual(EvidenceScorer.classify(confidence: 0.8, frame: -log(10), path: -log(10)).0, .red)
        XCTAssertEqual(EvidenceScorer.classify(confidence: 0.9, frame: -7, path: -7, flap: true).0, .yellow)
        XCTAssertEqual(EvidenceScorer.classify(confidence: 0.99, frame: 7, path: 7, ambiguous: true).0, .unknown)
        XCTAssertEqual(EvidenceScorer.classify(confidence: 0.69, frame: 7, path: 7).0, .unknown)
        XCTAssertEqual(EvidenceScorer.classify(confidence: 0.9, frame: 0, path: 0).0, .yellow)
        XCTAssertEqual(EvidenceScorer.summarize(Array(repeating: .green, count: 10)+[.red]).0, .red)
        XCTAssertEqual(EvidenceScorer.summarize([.green, .unknown]).0, .unknown)
        let scorer = try EvidenceScorer(logp: Array(repeating: Array(repeating: log(1.0/3), count: 3), count: 4), targets: [[1]], blank: 0, phoneIds: [1, 2])
        XCTAssertTrue(try scorer.replace(0, allowed: [2]).ambiguous)
    }
    func testInvalidInputsFailConservatively() {
        XCTAssertThrowsError(try EvidenceScorer(logp: [[.nan, 0]], targets: [[1]], blank: 0, phoneIds: [1]))
        XCTAssertThrowsError(try EvidenceScorer(logp: [[0, 0]], targets: [[0]], blank: 0, phoneIds: [1]))
    }
}
