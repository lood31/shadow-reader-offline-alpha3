import Foundation
import SwiftSoup

struct ImportedText { var title: String; var body: String; var source: String? }
enum SentenceSplitter {
    static let maxLength = 3000 // UTF-16 units, same limit as Android.
    static func split(_ body: String) -> [String] {
        let paragraphRegex = try! NSRegularExpression(pattern: "\\n\\s*\\n")
        let nsBody = body as NSString
        var paragraphs = [String](), start = 0
        for match in paragraphRegex.matches(in: body, range: NSRange(location: 0, length: nsBody.length)) {
            paragraphs.append(nsBody.substring(with: NSRange(location: start, length: match.range.location-start)))
            start = NSMaxRange(match.range)
        }
        paragraphs.append(nsBody.substring(from: start))
        let sentence = try! NSRegularExpression(pattern: "(?s).+?(?:[.!?][\"”’']*(?=\\s|$)|$)")
        let abbreviation = try! NSRegularExpression(pattern: "(?i).*(?:\\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs|etc|e\\.g|i\\.e)|\\b(?:[A-Z]\\.)*[A-Z])\\.$")
        var output = [String]()
        func chunks(_ value: String) {
            var remaining = value.trimmingCharacters(in: .whitespacesAndNewlines)
            while remaining.utf16.count > maxLength {
                var boundary = remaining.startIndex
                var units = 0
                for index in remaining.indices {
                    let size = String(remaining[index]).utf16.count
                    if units+size > maxLength { break }; units += size; boundary = remaining.index(after: index)
                }
                let prefix = remaining[..<boundary]
                let cut = prefix.lastIndex(of: " ").map { $0 > remaining.startIndex ? $0 : boundary } ?? boundary
                output.append(String(remaining[..<cut]).trimmingCharacters(in: .whitespaces))
                remaining = String(remaining[cut...]).trimmingCharacters(in: .whitespaces)
            }
            if remaining.rangeOfCharacter(from: .alphanumerics) != nil { output.append(remaining) }
        }
        for paragraph in paragraphs {
            let normalized = paragraph.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
            let matches = sentence.matches(in: normalized, range: NSRange(normalized.startIndex..., in: normalized))
            var pending = ""
            for (i, match) in matches.enumerated() {
                let piece = (normalized as NSString).substring(with: match.range).trimmingCharacters(in: .whitespaces)
                pending = pending.isEmpty ? piece : pending+" "+piece
                if abbreviation.firstMatch(in: pending, range: NSRange(pending.startIndex..., in: pending)) == nil || i == matches.count-1 {
                    chunks(pending); pending = ""
                }
            }
        }
        return output
    }
}
final class ArticleImporter: NSObject, URLSessionTaskDelegate {
    static let maxText = 200_000, maxHTML = 4_000_000
    static func fromText(title: String, body: String, source: String? = nil) throws -> ImportedText {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.utf16.count >= 10, text.utf16.count <= maxText else { throw ReaderError.message("正文需为10–20万字符，请分段导入。") }
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return ImportedText(title: String((name.isEmpty ? text.components(separatedBy: "\n").first! : name).prefix(name.isEmpty ? 70 : 180)), body: text, source: source)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url?.scheme == "https" ? request : nil)
    }
    func fromURL(_ raw: String) async throws -> ImportedText {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)), url.scheme == "https", url.host != nil, url.user == nil, url.password == nil else { throw ReaderError.message("请输入完整 HTTPS 地址，其他内容请粘贴导入。") }
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 35
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url); request.setValue("ShadowReader/2.0 (iOS article reader)", forHTTPHeaderField: "User-Agent")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), response.url?.scheme == "https", response.mimeType == nil || response.mimeType!.contains("html") else { throw ReaderError.message("网页请求失败或不是 HTML，请粘贴正文。") }
        var data = Data()
        for try await byte in bytes {
            try Task.checkCancellation(); data.append(byte)
            guard data.count <= Self.maxHTML else { throw ReaderError.message("网页超过4MB，请粘贴正文。") }
        }
        guard let html = String(data: data, encoding: .utf8) else { throw ReaderError.message("网页编码不支持，请粘贴正文。") }
        return try Self.extract(html: html, source: response.url!.absoluteString)
    }
    static func extract(html: String, source: String) throws -> ImportedText {
        let document = try SwiftSoup.parse(html, source)
        let title = try document.select("meta[property=og:title]").first()?.attr("content") ?? document.select("h1").first()?.text() ?? document.title()
        try document.select("script,style,noscript,nav,header,footer,aside,form,button,iframe,[hidden],[role=navigation],[role=banner],.advertisement,.ads,.cookie-banner,.social-share").remove()
        let preferred = try document.select("article,main,[role=main],[itemprop=articleBody],.post-content,.entry-content")
        let candidates = preferred.isEmpty() ? try document.select("body,section,div") : preferred
        var root = document.body(), best = -Double.infinity
        for element in candidates.array() {
            let text = try element.text(), links = try element.select("a").array().reduce(0) { try $0 + $1.text().utf16.count }
            let paragraphs = try element.select("p").array().filter { try $0.text().utf16.count > 80 }.count
            let score = Double(text.utf16.count-links*2+paragraphs*100)
            if score > best { best = score; root = element }
        }
        guard let root else { throw ReaderError.message("没有找到正文，请粘贴导入。") }
        var blocks = [String]()
        for element in try root.select("p,h2,h3,blockquote,li").array() {
            if try element.parents().array().contains(where: { $0 !== root && ["p", "blockquote", "li"].contains($0.tagName()) }) { continue }
            let text = try element.text(); if !text.isEmpty { blocks.append(text) }
        }
        let body = blocks.isEmpty ? try root.text() : blocks.joined(separator: "\n\n")
        guard body.utf16.count >= 40, body.filter(\.isLetter).count >= 20 else { throw ReaderError.message("网页没有可练习正文，可能需要登录或 JavaScript；请粘贴导入。") }
        return try fromText(title: title.isEmpty ? "Untitled article" : title, body: body, source: source)
    }
}
