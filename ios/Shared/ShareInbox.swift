import Foundation

struct SharedImport: Codable { var id: String; var title: String; var text: String?; var url: String? }
enum ShareInbox {
    static var group: String { (Bundle.main.object(forInfoDictionaryKey: "ShadowAppGroup") as? String) ?? "group.com.shadowreader.ios" }
    static func directory() throws -> URL {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { throw NSError(domain: "ShadowReader.Share", code: 1, userInfo: [NSLocalizedDescriptionKey: "分享导入需要为主应用和扩展配置相同 App Group 与签名。粘贴和网页地址导入仍可使用。"]) }
        let folder = container.appendingPathComponent("Inbox", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true); return folder
    }
    static func save(_ payload: SharedImport) throws {
        guard UUID(uuidString: payload.id) != nil, (payload.text?.utf16.count ?? 0) <= 200000, (payload.url?.count ?? 0) <= 8192 else { throw NSError(domain: "ShadowReader.Share", code: 2, userInfo: [NSLocalizedDescriptionKey: "分享内容过长，请分段粘贴导入。"]) }
        try JSONEncoder().encode(payload).write(to: directory().appendingPathComponent(payload.id+".json"), options: .atomic)
    }
    static func pending() throws -> [SharedImport] {
        let root = try directory()
        return try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]).filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }.compactMap { file in
            guard ((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? Int.max) <= 1_000_000,
                  let data = try? Data(contentsOf: file),
                  let payload = try? JSONDecoder().decode(SharedImport.self, from: data) else { return nil }
            guard UUID(uuidString: payload.id) != nil, file.lastPathComponent == payload.id+".json", (payload.text?.utf16.count ?? 0) <= 200000, (payload.url?.count ?? 0) <= 8192 else { return nil }
            return payload
        }
    }
    static func remove(_ payload: SharedImport) throws {
        guard UUID(uuidString: payload.id) != nil else { return }
        let file = try directory().appendingPathComponent(payload.id+".json")
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }
}
