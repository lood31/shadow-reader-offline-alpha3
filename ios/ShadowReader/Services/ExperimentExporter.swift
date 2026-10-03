import Foundation
import ZIPFoundation

enum ExperimentExporter {
    static func export(wav: Data, evidence: PronunciationAssessment) throws -> URL {
        guard FileDigest.sha256(wav) == evidence.audioSha256 else { throw ReaderError.message("导出录音与证据不匹配。") }
        let root = AppFiles.root.appendingPathComponent("Exports", isDirectory: true)
        let folder = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try wav.write(to: folder.appendingPathComponent("recording.wav"), options: .atomic)
        try EvidenceJSON.data(evidence).write(to: folder.appendingPathComponent("result.json"), options: .atomic)
        try Data(evidence.text.utf8).write(to: folder.appendingPathComponent("target.txt"), options: .atomic)
        try Data("iOS acoustic evidence experiment\nAudio stays local until the user explicitly shares this ZIP.\nNumeric pronunciation scores are null. Timestamps are estimates.\nMemory metric is physical footprint at completion, not Android PSS or a measured peak.\n".utf8).write(to: folder.appendingPathComponent("README.txt"))
        let archive = root.appendingPathComponent("ShadowReader-\(UUID().uuidString).zip")
        try FileManager.default.zipItem(at: folder, to: archive, shouldKeepParent: false)
        // Keep only the archive; every removed item is one known file in this new export folder.
        for name in ["recording.wav", "result.json", "target.txt", "README.txt"] { try FileManager.default.removeItem(at: folder.appendingPathComponent(name)) }
        try FileManager.default.removeItem(at: folder)
        return archive
    }
}
