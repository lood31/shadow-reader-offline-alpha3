import Foundation
import SQLite3

protocol TrainingRepository {
    func articles() throws -> [Article]
    func save(article: Article) throws
    func beginSession(id: String, review: Bool) throws
    func save(attempt: Attempt) throws
    func attempts(session: String) throws -> [Attempt]
    func save(review: SentenceReview) throws
    func reviews() throws -> [SentenceReview]
}
final class TrainingStore: TrainingRepository {
    private var database: OpaquePointer?
    private let encoder = JSONEncoder(), decoder = JSONDecoder()
    init(url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard sqlite3_open_v2(url.path, &database, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            if let database { sqlite3_close(database) }; database = nil
            throw ReaderError.message("本地数据库打开失败。")
        }
        do {
            try execute("PRAGMA foreign_keys=ON"); try execute("PRAGMA journal_mode=WAL")
            try execute("CREATE TABLE IF NOT EXISTS articles(id TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
            try execute("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY NOT NULL, started REAL NOT NULL, ended REAL, review INTEGER NOT NULL)")
            try execute("CREATE TABLE IF NOT EXISTS attempts(id TEXT PRIMARY KEY NOT NULL, session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, article TEXT NOT NULL REFERENCES articles(id) ON DELETE CASCADE, position INTEGER NOT NULL, created REAL NOT NULL, payload BLOB NOT NULL)")
            try execute("CREATE INDEX IF NOT EXISTS attempts_session ON attempts(session,created)")
            try execute("CREATE TABLE IF NOT EXISTS reviews(article TEXT NOT NULL REFERENCES articles(id) ON DELETE CASCADE, position INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(article,position))")
            try execute("PRAGMA user_version=1")
        } catch { sqlite3_close(database); database = nil; throw error }
    }
    deinit { sqlite3_close(database) }
    private func statement(_ sql: String) throws -> OpaquePointer {
        var value: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &value, nil) == SQLITE_OK, let value else { throw failure() }; return value
    }
    private func failure() -> ReaderError { .message("本地数据操作失败：\(database.map { String(cString: sqlite3_errmsg($0)) } ?? "database closed")") }
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    private func bind(_ text: String, _ index: Int32, _ query: OpaquePointer) { _ = text.withCString { sqlite3_bind_text(query, index, $0, -1, transient) } }
    private func bind(_ data: Data, _ index: Int32, _ query: OpaquePointer) { _ = data.withUnsafeBytes { sqlite3_bind_blob(query, index, $0.baseAddress, Int32(data.count), transient) } }
    private func finish(_ query: OpaquePointer) throws { guard sqlite3_step(query) == SQLITE_DONE else { throw failure() } }
    private func execute(_ sql: String) throws { guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw failure() } }
    private func fetch<T: Decodable>(_ sql: String, text: String? = nil) throws -> [T] {
        let query = try statement(sql); defer { sqlite3_finalize(query) }; if let text { bind(text, 1, query) }
        var values = [T]()
        while true {
            let status = sqlite3_step(query); if status == SQLITE_DONE { return values }
            guard status == SQLITE_ROW, let bytes = sqlite3_column_blob(query, 0) else { throw failure() }
            values.append(try decoder.decode(T.self, from: Data(bytes: bytes, count: Int(sqlite3_column_bytes(query, 0)))))
        }
    }
    func articles() throws -> [Article] { let values: [Article] = try fetch("SELECT payload FROM articles"); return values.sorted { $0.createdAt > $1.createdAt } }
    func save(article: Article) throws {
        let query = try statement("INSERT INTO articles(id,payload) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")
        defer { sqlite3_finalize(query) }; bind(article.id, 1, query); bind(try encoder.encode(article), 2, query); try finish(query)
    }
    func deleteArticle(_ id: String) throws {
        let query = try statement("DELETE FROM articles WHERE id=?"); defer { sqlite3_finalize(query) }; bind(id, 1, query); try finish(query)
    }
    func beginSession(id: String, review: Bool) throws {
        let query = try statement("INSERT INTO sessions(id,started,review) VALUES(?,?,?)"); defer { sqlite3_finalize(query) }
        bind(id, 1, query); sqlite3_bind_double(query, 2, Date().timeIntervalSince1970); sqlite3_bind_int(query, 3, review ? 1 : 0); try finish(query)
    }
    func endSession(_ id: String) throws {
        let query = try statement("UPDATE sessions SET ended=? WHERE id=?"); defer { sqlite3_finalize(query) }
        sqlite3_bind_double(query, 1, Date().timeIntervalSince1970); bind(id, 2, query); try finish(query)
    }
    func save(attempt: Attempt) throws {
        let query = try statement("INSERT INTO attempts(id,session,article,position,created,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")
        defer { sqlite3_finalize(query) }; bind(attempt.id, 1, query); bind(attempt.sessionId, 2, query); bind(attempt.articleId, 3, query)
        sqlite3_bind_int(query, 4, Int32(attempt.position)); sqlite3_bind_double(query, 5, attempt.createdAt.timeIntervalSince1970)
        bind(try encoder.encode(attempt), 6, query); try finish(query)
    }
    func attempts(session: String) throws -> [Attempt] { try fetch("SELECT payload FROM attempts WHERE session=? ORDER BY created,rowid", text: session) }
    func latestRecording(article: String, position: Int) throws -> Attempt? {
        let values: [Attempt] = try fetch("SELECT payload FROM attempts WHERE article=? ORDER BY created DESC,rowid DESC", text: article)
        return values.first { $0.position == position && $0.recordingName != nil }
    }
    func save(review: SentenceReview) throws {
        let query = try statement("INSERT INTO reviews(article,position,payload) VALUES(?,?,?) ON CONFLICT(article,position) DO UPDATE SET payload=excluded.payload")
        defer { sqlite3_finalize(query) }; bind(review.articleId, 1, query); sqlite3_bind_int(query, 2, Int32(review.position)); bind(try encoder.encode(review), 3, query); try finish(query)
    }
    func reviews() throws -> [SentenceReview] { try fetch("SELECT payload FROM reviews") }
    func removeReview(article: String, position: Int) throws {
        let query = try statement("DELETE FROM reviews WHERE article=? AND position=?"); defer { sqlite3_finalize(query) }
        bind(article, 1, query); sqlite3_bind_int(query, 2, Int32(position)); try finish(query)
    }
    func transaction<T>(_ operation: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do { let result = try operation(); try execute("COMMIT"); return result }
        catch { try? execute("ROLLBACK"); throw error }
    }
    func due(today: Date = Date()) throws -> [ReviewItem] {
        let day = Calendar.current.startOfDay(for: today), library = Dictionary(uniqueKeysWithValues: try articles().map { ($0.id, $0) })
        return try reviews().filter { $0.due.map { $0 <= day } ?? false }.sorted { ($0.due ?? .distantFuture) < ($1.due ?? .distantFuture) }.compactMap {
            guard let article = library[$0.articleId], article.sentences.indices.contains($0.position) else { return nil }
            return ReviewItem(article: article, position: $0.position)
        }
    }
    func updateReview(for attempt: Attempt, inReview: Bool) throws {
        guard [.consistent, .different].contains(attempt.result) else { return }
        var review = try reviews().first { $0.articleId == attempt.articleId && $0.position == attempt.position } ?? SentenceReview(articleId: attempt.articleId, position: attempt.position)
        if attempt.result == .different {
            review.streak += 1; review.difficult = review.difficult || review.streak >= 2
            (review.round, review.due) = TrainingRules.nextReview(today: Date(), round: review.round, passed: false)
            try save(review: review)
        } else {
            review.streak = 0
            if inReview && (review.due.map { $0 <= Calendar.current.startOfDay(for: Date()) } ?? false) {
                (review.round, review.due) = TrainingRules.nextReview(today: Date(), round: review.round, passed: true)
            }
            try save(review: review)
        }
    }
}
