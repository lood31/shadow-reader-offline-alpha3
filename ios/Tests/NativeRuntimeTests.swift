import XCTest
@testable import ShadowReader

final class NativeRuntimeTests: XCTestCase {
    func testCancelledNativeLoadCannotPoisonNextRequest() async throws {
        guard ProcessInfo.processInfo.environment["RUN_NATIVE_IOS_TESTS"] == "1" else { throw XCTSkip("Requires native Apple runtime and staged assets.") }
        let engine = LocalInference()
        let old = Task { try await engine.phonemesForTesting(String(repeating: "shadow reader pronunciation ", count: 100)) }
        try await Task.sleep(for: .milliseconds(20)); old.cancel()
        do { _ = try await old.value; XCTFail("Cancelled request must not publish a result") }
        catch is CancellationError {}
        catch { XCTFail("Cancellation must remain distinguishable from an inference failure: \(error)") }
        let recovered = try await engine.phonemesForTesting("reader")
        XCTAssertEqual(recovered, ["ɹ", "iː", "d", "ɚ"])
    }
    func test266WordG2PParityOnAppleRuntime() async throws {
        guard ProcessInfo.processInfo.environment["RUN_NATIVE_IOS_TESTS"] == "1" else { throw XCTSkip("Set RUN_NATIVE_IOS_TESTS=1 with staged assets; this loads native models.") }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "g2p", withExtension: "json", subdirectory: "Fixtures"))
        var golden = try JSONDecoder().decode([String: [String]].self, from: Data(contentsOf: url))
        let supplement = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "g2p-supplement", withExtension: "json", subdirectory: "Fixtures"))
        golden.merge(try JSONDecoder().decode([String: [String]].self, from: Data(contentsOf: supplement))) { original, _ in original }
        XCTAssertEqual(golden.count, 266)
        let engine = LocalInference()
        for word in golden.keys.sorted() { let phones = try await engine.phonemesForTesting(word); XCTAssertEqual(phones, golden[word], word) }
    }
}
