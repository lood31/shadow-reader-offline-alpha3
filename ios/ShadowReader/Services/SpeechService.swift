import Foundation
import AVFoundation

enum SpeechVoice: String, Codable, CaseIterable, Identifiable {
    case aria = "en-US-AriaNeural", guy = "en-US-GuyNeural", sonia = "en-GB-SoniaNeural", ryan = "en-GB-RyanNeural", systemUS = "system-US", systemUK = "system-UK"
    var id: String { rawValue }
    var online: Bool { self != .systemUS && self != .systemUK }
    var label: String { switch self { case .aria: return "Aria · 美式"; case .guy: return "Guy · 美式"; case .sonia: return "Sonia · 英式"; case .ryan: return "Ryan · 英式"; case .systemUS: return "系统语音 · 美式"; case .systemUK: return "系统语音 · 英式" } }
    var language: String { [.sonia, .ryan, .systemUK].contains(self) ? "en-GB" : "en-US" }
}
@MainActor final class SpeechService: SpeechProvider {
    var activeFile: URL?
    private var jobs = [String: Task<URL, Error>]()
    private var jobIDs = [String: UUID]()
    private var synthesizers = [UUID: SystemSynthesis]()
    func sentenceFile(text: String, voice: SpeechVoice) async throws -> URL {
        let key = FileDigest.sha256(Data((voice.rawValue+"|"+text).utf8))
        let target = AppFiles.cache.appendingPathComponent(key+(voice.online ? ".mp3" : ".caf"))
        if ((try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) > 100 {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path); return target
        }
        if let existing = jobs[key] { return try await existing.value }
        let jobID = UUID()
        let job = Task { () throws -> URL in
            let temporary = AppFiles.cache.appendingPathComponent(UUID().uuidString+(voice.online ? ".part" : ".caf"))
            defer { try? FileManager.default.removeItem(at: temporary) }
            if voice.online {
                let data = try await EdgeSpeech.synthesize(text: text, voice: voice)
                try Task.checkCancellation(); try data.write(to: temporary, options: .atomic)
            } else {
                let id = UUID(), synthesis = SystemSynthesis(); synthesizers[id] = synthesis
                defer { synthesizers[id] = nil }
                try await synthesis.write(text: text, language: voice.language, url: temporary)
            }
            try Task.checkCancellation()
            if FileManager.default.fileExists(atPath: target.path) { _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary) }
            else { try FileManager.default.moveItem(at: temporary, to: target) }
            trimCache(protect: target); return target
        }
        jobs[key] = job; jobIDs[key] = jobID
        defer { if jobIDs[key] == jobID { jobs[key] = nil; jobIDs[key] = nil } }
        return try await withTaskCancellationHandler(operation: { try await job.value }, onCancel: { job.cancel() })
    }
    func cancelAll() { for job in jobs.values { job.cancel() }; jobs.removeAll(); jobIDs.removeAll(); for synth in synthesizers.values { synth.cancel() } }
    private func trimCache(protect: URL) {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey, .isRegularFileKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: AppFiles.cache, includingPropertiesForKeys: keys)) ?? []
        let audios = files.filter { ["mp3", "caf"].contains($0.pathExtension) }
        var bytes = audios.reduce(0) { $0+((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        for file in audios.sorted(by: { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }) {
            if bytes <= 100*1024*1024 { break }
            if file == protect || file == activeFile { continue }
            let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if (try? FileManager.default.removeItem(at: file)) != nil { bytes -= size }
        }
    }
}
private enum EdgeSpeech {
    // Public Edge client token, same as Android; never a user credential.
    static let token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    static func synthesize(text: String, voice: SpeechVoice) async throws -> Data {
        let seconds = Int64(Date().timeIntervalSince1970)+11644473600
        let ticks = (seconds-seconds%300)*10_000_000
        let gec = FileDigest.sha256(Data((String(ticks)+token).utf8)).uppercased()
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let url = URL(string: "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=\(token)&ConnectionId=\(id)&Sec-MS-GEC=\(gec)&Sec-MS-GEC-Version=1-143.0.3650.75")!
        var request = URLRequest(url: url)
        request.setValue("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", forHTTPHeaderField: "Origin")
        request.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0", forHTTPHeaderField: "User-Agent")
        request.setValue("muid=\(UUID().uuidString.replacingOccurrences(of: "-", with: "").uppercased());", forHTTPHeaderField: "Cookie")
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 60
        let session = URLSession(configuration: config), socket = session.webSocketTask(with: request)
        defer { socket.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel() }
        return try await withTaskCancellationHandler(operation: {
            socket.resume()
            let timeout = Task { try await Task.sleep(for: .seconds(60)); socket.cancel(with: .goingAway, reason: nil) }
            defer { timeout.cancel() }
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(secondsFromGMT: 0)
            formatter.dateFormat = "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'"
            let stamp = formatter.string(from: Date())
            try await socket.send(.string("X-Timestamp:\(stamp)\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"))
            let escaped = text.filter { !$0.isNewline && !$0.unicodeScalars.contains(where: { $0.value < 32 }) }.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
            try await socket.send(.string("X-RequestId:\(id)\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:\(stamp)Z\r\nPath:ssml\r\n\r\n<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='\(voice.rawValue)'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>\(escaped)</prosody></voice></speak>"))
            var output = Data()
            while true {
                try Task.checkCancellation()
                let message = try await socket.receive()
                switch message {
                case .data(let frame):
                    if frame.count < 2 { continue }
                    let headerSize = Int(frame[0])*256+Int(frame[1])
                    guard headerSize+2 <= frame.count else { throw ReaderError.message("联网语音响应异常。") }
                    let headers = String(decoding: frame[2..<(headerSize+2)], as: UTF8.self)
                    if headers.contains("Path:audio") { output.append(frame[(headerSize+2)...]) }
                    guard output.count <= 4*1024*1024 else { throw ReaderError.message("联网语音过大，请练习较短句子。") }
                case .string(let frame):
                    if frame.contains("Path:turn.end") {
                        guard output.count > 100 else { throw ReaderError.message("联网语音未返回音频，请重试或手动选择系统语音。") }; return output
                    }
                @unknown default: break
                }
            }
        }, onCancel: { socket.cancel(with: .goingAway, reason: nil) })
    }
}
@MainActor private final class SystemSynthesis {
    private let synthesizer = AVSpeechSynthesizer()
    private var continuation: CheckedContinuation<Void, Error>?
    private let writer = SynthesisFile()
    func write(text: String, language: String, url: URL) async throws {
        try Task.checkCancellation()
        guard let voice = AVSpeechSynthesisVoice(language: language) else { throw ReaderError.message("请先在系统设置安装英语朗读语音。") }
        let utterance = AVSpeechUtterance(string: text); utterance.voice = voice; utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        let sink = writer
        let timeout = Task { try? await Task.sleep(for: .seconds(60)); if !Task.isCancelled { finish(ReaderError.message("系统语音生成超时，请确认已安装英语语音。")); synthesizer.stopSpeaking(at: .immediate) } }
        defer { timeout.cancel() }
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                synthesizer.write(utterance) { [weak self] buffer in
                    guard let buffer = buffer as? AVAudioPCMBuffer else { return }
                    // Write synchronously before AVFoundation can reuse its callback buffer.
                    do {
                        if try sink.append(buffer, url: url) {
                            Task { @MainActor in self?.finish(nil) }
                        }
                    } catch {
                        Task { @MainActor in self?.finish(error) }
                    }
                }
            }
        }, onCancel: { [weak self] in Task { @MainActor in self?.cancel() } })
    }
    private func finish(_ error: Error?) {
        let value = continuation; continuation = nil; writer.close()
        if let error { value?.resume(throwing: error) } else { value?.resume() }
    }
    func cancel() { synthesizer.stopSpeaking(at: .immediate); finish(CancellationError()) }
}

private final class SynthesisFile: @unchecked Sendable {
    private let lock = NSLock()
    private var file: AVAudioFile?
    private var closed = false
    func append(_ buffer: AVAudioPCMBuffer, url: URL) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { return false }
        if buffer.frameLength == 0 { file = nil; closed = true; return true }
        if file == nil { file = try AVAudioFile(forWriting: url, settings: buffer.format.settings) }
        try file!.write(from: buffer); return false
    }
    func close() { lock.lock(); file = nil; closed = true; lock.unlock() }
}
