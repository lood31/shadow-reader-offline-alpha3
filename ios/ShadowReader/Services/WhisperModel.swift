import Foundation
import Combine

@MainActor final class WhisperModel: ObservableObject {
    nonisolated static let size = 59_721_011
    nonisolated static let sha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
    nonisolated static let source = URL(string: "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin")!
    nonisolated static var file: URL { AppFiles.models.appendingPathComponent("ggml-base.en-q5_1.bin") }
    @Published var ready = false
    @Published var progress = 0.0
    @Published var downloading = false
    @Published var message = ""
    private var task: Task<Void, Never>?
    init() { ready = (try? Self.file.resourceValues(forKeys: [.fileSizeKey]).fileSize) == Self.size }
    func download() {
        guard !downloading else { return }; downloading = true; message = ""; progress = 0
        task = Task {
            let downloader = ModelDownloader()
            do {
                let temporary = try await downloader.download { [weak self] value in Task { @MainActor in self?.progress = value } }
                defer { try? FileManager.default.removeItem(at: temporary) }
                message = "正在校验模型…"
                let flag = CancellationFlag()
                let digest = try await withTaskCancellationHandler(operation: {
                    try Task.checkCancellation()
                    return try await Task.detached { try FileDigest.sha256(file: temporary, check: flag.check) }.value
                }, onCancel: { flag.cancel() })
                try Task.checkCancellation()
                guard digest == Self.sha256, try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize == Self.size else { throw ReaderError.message("模型校验失败，请重新下载。") }
                if FileManager.default.fileExists(atPath: Self.file.path) { _ = try FileManager.default.replaceItemAt(Self.file, withItemAt: temporary) }
                else { try FileManager.default.moveItem(at: temporary, to: Self.file) }
                ready = true; progress = 1; message = "离线识别模型已就绪。"
            } catch is CancellationError { message = "下载已取消。" }
            catch { message = error.localizedDescription }
            downloading = false; task = nil
        }
    }
    func cancel() { task?.cancel() }
}
private final class ModelDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<URL, Error>?
    private var session: URLSession?
    private var task: URLSessionDownloadTask?
    private var cancelled = false
    private var progress: ((Double) -> Void)?
    func download(progress: @escaping (Double) -> Void) async throws -> URL {
        self.progress = progress
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { value in
                lock.lock(); continuation = value
                if cancelled { continuation = nil; lock.unlock(); value.resume(throwing: CancellationError()); return }
                let configuration = URLSessionConfiguration.ephemeral; configuration.timeoutIntervalForRequest = 30; configuration.timeoutIntervalForResource = 3600
                session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                task = session!.downloadTask(with: WhisperModel.source); task!.resume(); lock.unlock()
            }
        }, onCancel: { [self] in lock.lock(); cancelled = true; let task = task; lock.unlock(); task?.cancel() })
    }
    private func finish(_ result: Result<URL, Error>) {
        lock.lock(); let value = continuation; continuation = nil; lock.unlock()
        value?.resume(with: result); session?.finishTasksAndInvalidate()
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > WhisperModel.size { downloadTask.cancel(); return }
        progress?(min(1, Double(totalBytesWritten)/Double(WhisperModel.size)))
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(request.url?.scheme == "https" ? request : nil) }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, (200..<300).contains(response.statusCode), response.url?.scheme == "https" else { throw ReaderError.message("模型下载失败，请重试。") }
            let temporary = AppFiles.models.appendingPathComponent(UUID().uuidString+".download")
            try FileManager.default.copyItem(at: location, to: temporary); finish(.success(temporary))
        } catch { finish(.failure(error)) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure((error as NSError).code == NSURLErrorCancelled ? CancellationError() : error)) }
    }
}
