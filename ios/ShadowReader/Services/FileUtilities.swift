import Foundation
import CryptoKit

enum AppFiles {
    static var root: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("ShadowReader", isDirectory: true) }
    static var recordings: URL { root.appendingPathComponent("Recordings", isDirectory: true) }
    static var models: URL { root.appendingPathComponent("Models", isDirectory: true) }
    static var cache: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("ShadowSpeech", isDirectory: true) }
    static func prepare() throws {
        for directory in [root, recordings, models, cache] {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var url = directory; var values = URLResourceValues(); values.isExcludedFromBackup = true; try url.setResourceValues(values)
        }
    }
    static func recording(_ name: String) throws -> URL {
        guard !name.isEmpty, name == URL(fileURLWithPath: name).lastPathComponent, !name.contains("/"), !name.contains("\\") else { throw ReaderError.message("录音路径无效。") }
        return recordings.appendingPathComponent(name)
    }
}
enum FileDigest {
    static func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    static func sha256(file: URL, check: () throws -> Void = {}) throws -> String {
        let stream = try FileHandle(forReadingFrom: file); defer { try? stream.close() }
        var digest = SHA256()
        while true { try check(); let bytes = try stream.read(upToCount: 65_536) ?? Data(); if bytes.isEmpty { break }; digest.update(data: bytes) }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
enum PcmWav {
    static func quantized(_ samples: [Float]) throws -> [Int16] {
        guard samples.count >= 8000, samples.count <= 480000, samples.allSatisfy(\.isFinite) else { throw ReaderError.message("发音评估支持0.5–30秒录音，录音仍保留。") }
        return samples.map { Int16(min(32767, max(-32768, Int(floor(Double(min(1, max(-1, $0))*32768)+0.5))))) }
    }
    static func encode(_ samples: [Float]) throws -> Data {
        let pcm = try quantized(samples); var output = Data()
        func word(_ value: UInt16) { var little = value.littleEndian; withUnsafeBytes(of: &little) { output.append(contentsOf: $0) } }
        func integer(_ value: UInt32) { var little = value.littleEndian; withUnsafeBytes(of: &little) { output.append(contentsOf: $0) } }
        output.append(contentsOf: "RIFF".utf8); integer(UInt32(36+pcm.count*2)); output.append(contentsOf: "WAVEfmt ".utf8)
        integer(16); word(1); word(1); integer(16000); integer(32000); word(2); word(16)
        output.append(contentsOf: "data".utf8); integer(UInt32(pcm.count*2))
        for value in pcm { word(UInt16(bitPattern: value)) }; return output
    }
    static func floatData(_ samples: [Float]) -> Data { samples.withUnsafeBytes { Data($0) } }
}
final class CancellationFlag: @unchecked Sendable {
    private let lock = NSLock(); private var cancelled = false
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    func check() throws { lock.lock(); let value = cancelled; lock.unlock(); if value { throw CancellationError() } }
}
