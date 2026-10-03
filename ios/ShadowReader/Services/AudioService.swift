import AVFoundation
import Foundation
import Combine

@MainActor protocol TrainingAudio: AnyObject {
    var recording: Bool { get }
    func startRecording(at url: URL) async throws
    @discardableResult func finishRecording() -> URL?
    func play(_ url: URL, speed: Double) async throws -> Double
    func stop()
}

@MainActor
final class AudioService: NSObject, ObservableObject, TrainingAudio {
    @Published var recording = false
    @Published var paused = false
    @Published var playing = false
    private var recorder: AVAudioRecorder?
    private var player: AVPlayer?
    private var observation: NSKeyValueObservation?
    private var continuation: CheckedContinuation<Double, Error>?
    private var timing = PlaybackTiming()
    private var finishObserver: NSObjectProtocol?
    private var errorObserver: NSObjectProtocol?
    private var clipTask: Task<Void, Never>?
    private var playbackID = UUID()
    var onInterruption: (() -> Void)?
    override init() {
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(interrupted), name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(routeChanged), name: AVAudioSession.routeChangeNotification, object: nil)
    }
    deinit { NotificationCenter.default.removeObserver(self) }
    @objc private func interrupted(_ notification: Notification) {
        guard let value = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, value == AVAudioSession.InterruptionType.began.rawValue else { return }
        onInterruption?()
    }
    @objc private func routeChanged(_ notification: Notification) {
        guard let value = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt, value == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
        onInterruption?()
    }
    func startRecording(at url: URL) async throws {
        stop()
        let allowed = await AVAudioApplication.requestRecordPermission()
        try Task.checkCancellation()
        guard allowed else { throw ReaderError.message("麦克风权限未开启，请在系统设置中允许录音。") }
        try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try AVAudioSession.sharedInstance().setActive(true)
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100.0, AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue]
        recorder = try AVAudioRecorder(url: url, settings: settings)
        guard recorder!.record(forDuration: 120) else { throw ReaderError.message("无法开始录音，请重试。") }
        recording = true; paused = false
    }
    @discardableResult func finishRecording() -> URL? {
        let url = recorder?.url; recorder?.stop(); recorder = nil; recording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation); return url
    }
    func play(_ url: URL, speed: Double) async throws -> Double {
        stop(); try Task.checkCancellation()
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try AVAudioSession.sharedInstance().setActive(true)
        let id = UUID(); playbackID = id
        let item = AVPlayerItem(url: url); item.audioTimePitchAlgorithm = .timeDomain
        player = AVPlayer(playerItem: item); timing = PlaybackTiming(); paused = false
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                observation = player?.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
                    let active = player.timeControlStatus == .playing
                    Task { @MainActor in guard let self, self.playbackID == id else { return }; self.timing.playing(active); self.playing = active }
                }
                finishObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                    Task { @MainActor in guard self?.playbackID == id else { return }; self?.complete(nil) }
                }
                errorObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] note in
                    let error = (note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error) ?? ReaderError.message("音频播放失败。")
                    Task { @MainActor in guard self?.playbackID == id else { return }; self?.complete(error) }
                }
                player?.playImmediately(atRate: Float(PlaybackSettings.speed(speed)))
            }
        }, onCancel: { [weak self] in Task { @MainActor in guard self?.playbackID == id else { return }; self?.stop() } })
    }
    private func complete(_ error: Error?) {
        guard let continuation else { return }; self.continuation = nil
        let elapsed = timing.finish(); cleanupPlayer()
        if let error { continuation.resume(throwing: error) } else { continuation.resume(returning: elapsed) }
    }
    private func cleanupPlayer() {
        observation = nil; player?.pause(); player = nil; playing = false; paused = false
        if let finishObserver { NotificationCenter.default.removeObserver(finishObserver) }; finishObserver = nil
        if let errorObserver { NotificationCenter.default.removeObserver(errorObserver) }; errorObserver = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    func stop() { playbackID = UUID(); clipTask?.cancel(); clipTask = nil; complete(CancellationError()); if player != nil { cleanupPlayer() } }
    func pause() { paused = true; timing.playing(false); player?.pause() }
    func resume(speed: Double) { paused = false; player?.playImmediately(atRate: Float(PlaybackSettings.speed(speed))) }
    func setSpeed(_ speed: Double) { if !paused { player?.rate = Float(PlaybackSettings.speed(speed)) } }
    func playClip(_ url: URL, startMs: Int?, endMs: Int?, speed: Double = 1) {
        stop(); let id = UUID(); playbackID = id
        clipTask = Task {
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio); try AVAudioSession.sharedInstance().setActive(true)
                let item = AVPlayerItem(url: url); item.audioTimePitchAlgorithm = .timeDomain
                let playback = AVPlayer(playerItem: item); player = playback
                let start = max(0, Double(startMs ?? 0)/1000)
                if let endMs { item.forwardPlaybackEndTime = CMTime(seconds: max(start, Double(endMs)/1000), preferredTimescale: 16000) }
                await playback.seek(to: CMTime(seconds: start, preferredTimescale: 16000), toleranceBefore: .zero, toleranceAfter: .zero)
                try Task.checkCancellation(); guard playbackID == id else { return }; playback.playImmediately(atRate: Float(speed)); playing = true
                let length: Double
                if let endMs { length = Double(endMs)/1000-start }
                else { length = try await AVURLAsset(url: url).load(.duration).seconds-start }
                try await Task.sleep(for: .seconds(max(0.1, length/speed)))
                if playbackID == id { cleanupPlayer() }
            } catch { if playbackID == id { cleanupPlayer() } }
        }
    }
}
enum PcmDecoder {
    static func decode(_ url: URL, check: () throws -> Void = {}) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        guard file.length > 0, file.length <= AVAudioFramePosition(file.processingFormat.sampleRate*121), let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false), let converter = AVAudioConverter(from: file.processingFormat, to: format) else { throw ReaderError.message("录音格式无效或超过两分钟，未评估。") }
        var result = [Float](), ended = false
        while !ended {
            try check(); let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4096)!
            var conversionError: NSError?, readError: Error?
            let status = converter.convert(to: output, error: &conversionError) { count, state in
                if file.framePosition >= file.length { state.pointee = .endOfStream; return nil }
                let input = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: max(count, 4096))!
                do { try file.read(into: input); state.pointee = .haveData; return input }
                catch { readError = error; state.pointee = .endOfStream; return nil }
            }
            if let conversionError { throw conversionError }
            if let readError { throw readError }
            guard status != .error else { throw ReaderError.message("录音解码失败，未评估。") }
            if let samples = output.floatChannelData?[0] { result.append(contentsOf: UnsafeBufferPointer(start: samples, count: Int(output.frameLength))) }
            guard result.count <= 1_936_000 else { throw ReaderError.message("录音过长，未评估。") }
            ended = status == .endOfStream
        }
        return result
    }
}
