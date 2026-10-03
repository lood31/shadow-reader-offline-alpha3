import XCTest
@testable import ShadowReader

final class StoreTests: XCTestCase {
    func testPersistenceReopenForeignKeysAndArticleDelete() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let url = root.appendingPathComponent("test.sqlite")
        var store: TrainingStore? = try TrainingStore(url: url)
        let article = Article(title: "Example", body: "Hello world.", sentences: ["Hello world."])
        try store!.save(article: article); try store!.beginSession(id: "s", review: false)
        var attempt = Attempt(sessionId: "s", articleId: article.id, position: 0, text: "Hello world."); attempt.recordingName = "take.m4a"
        try store!.save(attempt: attempt); store = nil
        let reopened = try TrainingStore(url: url)
        XCTAssertEqual(try reopened.articles().first?.id, article.id)
        XCTAssertEqual(try reopened.attempts(session: "s").first?.recordingName, "take.m4a")
        var invalid = attempt; invalid.id = "invalid"; invalid.articleId = "missing"
        XCTAssertThrowsError(try reopened.save(attempt: invalid))
        try reopened.deleteArticle(article.id); XCTAssertTrue(try reopened.attempts(session: "s").isEmpty)
    }
    func testRollbackAndDisputedAttemptsCannotAdvanceReview() throws {
        let store = try TrainingStore(url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString+".sqlite"))
        let article = Article(title: "Example", body: "Hello world.", sentences: ["Hello world."])
        XCTAssertThrowsError(try store.transaction { try store.save(article: article); throw ReaderError.message("rollback") })
        XCTAssertTrue(try store.articles().isEmpty); try store.save(article: article); try store.beginSession(id: "s", review: true)
        let review = SentenceReview(articleId: article.id, position: 0, difficult: true, streak: 0, round: 2, due: Calendar.current.startOfDay(for: Date()))
        try store.save(review: review)
        var attempt = Attempt(sessionId: "s", articleId: article.id, position: 0, text: "Hello world.")
        for outcome in [AttemptResult.skipped, .unevaluated, .disputed] { attempt.result = outcome; try store.updateReview(for: attempt, inReview: true); XCTAssertEqual(try store.reviews().first?.round, 2) }
        attempt.result = .consistent; try store.updateReview(for: attempt, inReview: true); XCTAssertEqual(try store.reviews().first?.round, 3)
        attempt.result = .different; try store.updateReview(for: attempt, inReview: true); XCTAssertEqual(try store.reviews().first?.round, 0)
    }
}
