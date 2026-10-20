import Foundation

/// The inspected media properties needed to decide whether a movie can stream without remuxing.
struct VideoProbe: Sendable, Hashable {
    let videoCodec: String
    let width: Int
    let height: Int
    let duration: TimeInterval?
    let audioCodecs: [String]
    let formatNames: [String]
    let pixelFormat: String?
    let colorSpace: String?
    let colorTransfer: String?
    let colorPrimaries: String?

    var isAtMost4K: Bool {
        width > 0 && height > 0 && width <= 3_840 && height <= 2_160 && Int64(width) * Int64(height) <= 8_294_400
    }

    var needsHDRToSDR: Bool { ["smpte2084", "arib-std-b67"].contains(colorTransfer) }
}

struct CompatibleVideoURL: Sendable, Hashable {
    let url: URL
    let source: URL
    let wasTranscoded: Bool
    let probe: VideoProbe
    /// Ownership transfers to the immediate consumer. It must release this token after the
    /// result has been persisted or handed to the movie stream server.
    let cacheUse: ConversionCacheUseToken?
}

enum VideoCompatibilityStatus: Sendable, Hashable {
    case probing
    case direct
    case converting(fraction: Double?)
    case verifying
    case completed(CompatibleVideoURL)
}

enum VideoCompatibilityError: LocalizedError, Sendable {
    case ffprobeUnavailable
    case ffmpegUnavailable
    case sourceUnreadable
    case noVideoStream
    case inspectionFailed(String)
    case conversionFailed(String)
    case invalidOutput
    case cacheContainsInvalidOutput(URL)
    case cacheLimitReached

    var errorDescription: String? {
        switch self {
        case .ffprobeUnavailable: return "ffprobe is required to inspect this video. Add it to PATH or install it in /opt/homebrew/bin or /usr/local/bin."
        case .ffmpegUnavailable: return "ffmpeg is required to prepare this video. Add it to PATH or install it in /opt/homebrew/bin or /usr/local/bin."
        case .sourceUnreadable: return "This video file is no longer available."
        case .noVideoStream: return "This file does not contain a video stream."
        case .inspectionFailed: return "This video could not be inspected."
        case .conversionFailed: return "This video could not be converted for streaming."
        case .invalidOutput: return "The converted video could not be verified."
        case .cacheContainsInvalidOutput: return "A previous converted copy is invalid. Remove it from the app's Video Compatibility cache before trying again."
        case .cacheLimitReached: return "The video conversion cache has reached its storage limit. Clear cached conversions or increase the limit before preparing another video."
        }
    }
}

/// Prepares an ffmpeg-readable source for the TV's conservative MP4 playback profile.
///
/// This type has no UI affinity. Call it from a Task and marshal status updates to the main
/// actor when they drive UI. Cancelling that Task terminates its active ffprobe or ffmpeg process.
enum VideoCompatibility {
    private static let maximumWidth = 3_840
    private static let maximumHeight = 2_160
    private static let maximumPixels: Int64 = 8_294_400
    private static let cacheProfile = "sdr-h264-v3-hdr-tonemap-30fps"
    private static let supportedAudioCodecs: Set<String> = ["aac", "ac3", "eac3", "mp3"]

    /// Inspects the primary video stream and every audio stream with ffprobe.
    static func probe(_ source: URL) async throws -> VideoProbe {
        guard FileManager.default.isReadableFile(atPath: source.path) else { throw VideoCompatibilityError.sourceUnreadable }
        guard let ffprobeURL = MediaToolLocator.executable(named: "ffprobe") else { throw VideoCompatibilityError.ffprobeUnavailable }

        let result = try await ToolProcess.run(
            executable: ffprobeURL,
            arguments: [
                "-v", "error", "-show_entries", "format=format_name,duration:stream=codec_type,codec_name,width,height,pix_fmt,color_space,color_transfer,color_primaries",
                "-of", "json", source.path
            ]
        )
        guard result.status == 0 else { throw VideoCompatibilityError.inspectionFailed(result.stderr) }
        guard !result.stdoutTruncated,
              let payload = try? JSONDecoder().decode(FFProbePayload.self, from: result.stdout),
              let video = payload.streams.first(where: { $0.codecType == "video" }),
              let codec = video.codecName?.lowercased(),
              let width = video.width, let height = video.height, width > 0, height > 0 else {
            throw VideoCompatibilityError.noVideoStream
        }
        let duration = payload.format?.duration.flatMap(TimeInterval.init)
        return VideoProbe(
            videoCodec: codec,
            width: width,
            height: height,
            duration: duration,
            audioCodecs: payload.streams.compactMap { $0.codecType == "audio" ? $0.codecName?.lowercased() : nil },
            formatNames: (payload.format?.formatName ?? "").split(separator: ",").map { $0.lowercased() },
            pixelFormat: video.pixelFormat?.lowercased(),
            colorSpace: video.colorSpace?.lowercased(),
            colorTransfer: video.colorTransfer?.lowercased(),
            colorPrimaries: video.colorPrimaries?.lowercased()
        )
    }

    /// Returns the source URL when it already satisfies the streaming profile; otherwise returns
    /// an atomically-created, verified MP4 in Application Support/Glass TV/Video Compatibility.
    static func compatibleURL(
        for source: URL,
        progress: @escaping @Sendable (VideoCompatibilityStatus) -> Void = { _ in }
    ) async throws -> CompatibleVideoURL {
        try Task.checkCancellation()
        progress(.probing)
        let inspected = try await probe(source)
        try Task.checkCancellation()

        if isDirectlyCompatible(source: source, probe: inspected) {
            let direct = CompatibleVideoURL(url: source, source: source, wasTranscoded: false, probe: inspected, cacheUse: nil)
            progress(.direct)
            progress(.completed(direct))
            return direct
        }

        guard let ffmpegURL = MediaToolLocator.executable(named: "ffmpeg") else { throw VideoCompatibilityError.ffmpegUnavailable }
        let cache = try cacheURL(for: source)
        if FileManager.default.fileExists(atPath: cache.path) {
            let cacheUse = await ConversionCache.shared.acquireUse(for: cache)
            var transferred = false
            defer {
                if !transferred, let cacheUse { Task { await ConversionCache.shared.releaseUse(cacheUse) } }
            }
            guard try await isVerifiedCompatibleMP4(cache) else { throw VideoCompatibilityError.cacheContainsInvalidOutput(cache) }
            let cached = CompatibleVideoURL(url: cache, source: source, wasTranscoded: true, probe: try await probe(cache), cacheUse: cacheUse)
            transferred = true
            progress(.completed(cached))
            return cached
        }

        let ticket: ConversionCacheConversionTicket
        do {
            ticket = try await ConversionCache.shared.beginConversion(at: cache)
        } catch ConversionCacheError.limitReached {
            throw VideoCompatibilityError.cacheLimitReached
        }
        defer { Task { await ConversionCache.shared.finishConversion(ticket) } }

        let temporary = cache.deletingLastPathComponent().appendingPathComponent(".\(cache.deletingPathExtension().lastPathComponent).\(UUID().uuidString).incomplete.mp4")
        defer { try? FileManager.default.removeItem(at: temporary) }
        progress(.converting(fraction: 0))
        do {
            try await transcode(source: source, temporary: temporary, duration: inspected.duration, codec: "h264_videotoolbox", progress: progress)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            try await transcode(source: source, temporary: temporary, duration: inspected.duration, codec: "libx264", progress: progress)
        }
        try Task.checkCancellation()
        progress(.verifying)
        guard try await isVerifiedCompatibleMP4(temporary) else { throw VideoCompatibilityError.invalidOutput }
        do {
            try await ConversionCache.shared.admit(temporary, for: ticket)
        } catch ConversionCacheError.limitReached {
            throw VideoCompatibilityError.cacheLimitReached
        }

        do {
            try FileManager.default.moveItem(at: temporary, to: cache)
        } catch {
            // Another concurrent caller may have won the atomic publish race. Never replace an
            // existing cache entry, including a bad one, because it may belong to another run.
            guard FileManager.default.fileExists(atPath: cache.path), try await isVerifiedCompatibleMP4(cache) else {
                if FileManager.default.fileExists(atPath: cache.path) { throw VideoCompatibilityError.cacheContainsInvalidOutput(cache) }
                throw error
            }
        }
        // Acquire before releasing the conversion ticket so Clear cache always sees at least
        // one protection record through the publish-to-consumer handoff.
        let verifiedProbe = try await probe(cache)
        let cacheUse = await ConversionCache.shared.acquireUse(for: cache)
        let result = CompatibleVideoURL(url: cache, source: source, wasTranscoded: true, probe: verifiedProbe, cacheUse: cacheUse)
        progress(.completed(result))
        return result
    }

    private static func isDirectlyCompatible(source: URL, probe: VideoProbe) -> Bool {
        source.pathExtension.lowercased() == "mp4"
            && ["h264", "hevc"].contains(probe.videoCodec)
            && probe.isAtMost4K
            && probe.audioCodecs.allSatisfy { supportedAudioCodecs.contains($0) }
    }

    private static func cacheURL(for source: URL) throws -> URL {
        let values = try source.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .fileResourceIdentifierKey])
        guard let size = values.fileSize, let modified = values.contentModificationDate else { throw VideoCompatibilityError.sourceUnreadable }
        let identity = "\(cacheProfile)\\n\(source.standardizedFileURL.path.precomposedStringWithCanonicalMapping)\\n\(size)\\n\(modified.timeIntervalSinceReferenceDate)\\n\(String(describing: values.fileResourceIdentifier))"
        let directory = AppPaths.applicationSupport.appendingPathComponent("Video Compatibility", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("\(sha256Hex(Data(identity.utf8))).mp4")
    }

    private static func transcode(
        source: URL,
        temporary: URL,
        duration: TimeInterval?,
        codec: String,
        progress: @escaping @Sendable (VideoCompatibilityStatus) -> Void
    ) async throws {
        let scale = "scale=w='min(3840,iw)':h='min(2160,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
        let filter: String
        if let hdrTransfer = (try? await probe(source).colorTransfer), ["smpte2084", "arib-std-b67"].contains(hdrTransfer) {
            let lut = try hdrToSDRLUT(transfer: hdrTransfer)
            filter = "\(scale),fps=30,format=gbrpf32le,lut3d=file='\(lut.path.replacingOccurrences(of: "'", with: "\\\\'"))',colorspace=iall=bt709:all=bt709:format=yuv420p:fast=1"
        } else {
            filter = "\(scale),fps=30"
        }
        guard let ffmpegURL = MediaToolLocator.executable(named: "ffmpeg") else { throw VideoCompatibilityError.ffmpegUnavailable }
        let output = try await ToolProcess.run(
            executable: ffmpegURL,
            arguments: [
                "-hide_banner", "-nostdin", "-v", "error", "-y", "-i", source.path,
                "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "-1",
                "-vf", filter, "-pix_fmt", "yuv420p",
                "-c:v", codec, "-b:v", "6M", "-maxrate", "6M", "-bufsize", "12M",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-f", "mp4",
                "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                "-progress", "pipe:1", "-nostats", temporary.path
            ],
            onOutputLine: { line in
                guard let duration, duration > 0 else { return }
                let parts = line.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard parts.count == 2, parts[0] == "out_time_us", let microseconds = Double(parts[1]) else { return }
                progress(.converting(fraction: min(1, max(0, microseconds / 1_000_000 / duration))))
            }
        )
        guard output.status == 0 else { throw VideoCompatibilityError.conversionFailed(output.stderr) }
    }

    private static func isVerifiedCompatibleMP4(_ url: URL) async throws -> Bool {
        guard FileManager.default.fileExists(atPath: url.path),
              let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize), size > 0 else { return false }
        let inspected = try await probe(url)
        return url.pathExtension.lowercased() == "mp4"
            && inspected.formatNames.contains("mov") && inspected.formatNames.contains("mp4")
            && inspected.videoCodec == "h264" && inspected.isAtMost4K
            && inspected.audioCodecs.allSatisfy { $0 == "aac" }
    }

    static func hdrToSDRLUT(transfer: String) throws -> URL {
        let directory = AppPaths.applicationSupport.appendingPathComponent("Video Compatibility", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appendingPathComponent("hdr-to-sdr-\(transfer)-bt2020-to-bt709-v2.cube")
        if FileManager.default.fileExists(atPath: target.path) { return target }

        let size = 33
        var contents = "TITLE \"Glass TV HDR to SDR BT.2020 to BT.709\"\nLUT_3D_SIZE \(size)\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n"
        for blue in 0..<size {
            for green in 0..<size {
                for red in 0..<size {
                    let rgb = toneMapBT2020ToBT709(
                        red: Double(red) / Double(size - 1),
                        green: Double(green) / Double(size - 1),
                        blue: Double(blue) / Double(size - 1),
                        transfer: transfer
                    )
                    contents += String(format: "%.8f %.8f %.8f\n", rgb.0, rgb.1, rgb.2)
                }
            }
        }
        let temporary = directory.appendingPathComponent(".\(target.lastPathComponent).\(UUID().uuidString).incomplete")
        try contents.data(using: .utf8)?.write(to: temporary, options: .atomic)
        do { try FileManager.default.moveItem(at: temporary, to: target) }
        catch {
            try? FileManager.default.removeItem(at: temporary)
            guard FileManager.default.fileExists(atPath: target.path) else { throw error }
        }
        return target
    }

    private static func toneMapBT2020ToBT709(red: Double, green: Double, blue: Double, transfer: String) -> (Double, Double, Double) {
        let linear: (Double, Double, Double) = (decodeHDR(red, transfer: transfer), decodeHDR(green, transfer: transfer), decodeHDR(blue, transfer: transfer))
        var r = 1.6604910 * linear.0 - 0.5876411 * linear.1 - 0.0728499 * linear.2
        var g = -0.1245505 * linear.0 + 1.1328999 * linear.1 - 0.0083494 * linear.2
        var b = -0.0181508 * linear.0 - 0.1005789 * linear.1 + 1.1187297 * linear.2
        r = max(0, r); g = max(0, g); b = max(0, b)
        let luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        if luminance > 0 {
            // HDR10 is absolute up to 10,000 nits. This Hable curve maps a 1,000-nit
            // reference white to SDR white while preserving hue through a shared scale.
            let mapped = hable(luminance * 100) / hable(11.2)
            let scale = mapped / luminance
            r *= scale; g *= scale; b *= scale
        }
        return (encodeBT709(min(1, r)), encodeBT709(min(1, g)), encodeBT709(min(1, b)))
    }

    private static func decodeHDR(_ value: Double, transfer: String) -> Double {
        let encoded = min(1, max(0, value))
        if transfer == "arib-std-b67" {
            // HLG is scene-referred. Normalize its conventional 1,000-nit reference to the
            // PQ branch's 10,000-nit normalized scale before the common tone-mapping curve.
            let relative = encoded <= 0.5 ? encoded * encoded / 3 : (exp((encoded - 0.55991073) / 0.17883277) + 0.28466892) / 12
            return relative / 10
        }
        let power = pow(encoded, 1 / 78.84375)
        return pow(max(0, (power - 0.8359375) / (18.8515625 - 18.6875 * power)), 1 / 0.1593017578125)
    }

    private static func hable(_ value: Double) -> Double {
        let a = 0.15, b = 0.50, c = 0.10, d = 0.20, e = 0.02, f = 0.30
        return ((value * (a * value + c * b) + d * e) / (value * (a * value + b) + d * f)) - e / f
    }

    private static func encodeBT709(_ value: Double) -> Double {
        value < 0.018 ? 4.5 * value : 1.099 * pow(value, 0.45) - 0.099
    }
}

private struct FFProbePayload: Decodable {
    struct Stream: Decodable {
        let codecType: String?
        let codecName: String?
        let width: Int?
        let height: Int?
        let pixelFormat: String?
        let colorSpace: String?
        let colorTransfer: String?
        let colorPrimaries: String?

        private enum CodingKeys: String, CodingKey {
            case codecType = "codec_type", codecName = "codec_name", width, height
            case pixelFormat = "pix_fmt", colorSpace = "color_space", colorTransfer = "color_transfer", colorPrimaries = "color_primaries"
        }
    }

    struct Format: Decodable {
        let formatName: String?
        let duration: String?

        private enum CodingKeys: String, CodingKey { case formatName = "format_name", duration }
    }

    let streams: [Stream]
    let format: Format?
}

private final class ToolProcess: @unchecked Sendable {
    struct Output: Sendable {
        let status: Int32
        let stdout: Data
        let stderr: String
        let stdoutTruncated: Bool
    }

    private let lock = NSLock()
    private var process: Process?
    private var cancelled = false

    static func run(
        executable: URL,
        arguments: [String],
        onOutputLine: @escaping @Sendable (String) -> Void = { _ in }
    ) async throws -> Output {
        let runner = ToolProcess()
        return try await withTaskCancellationHandler(operation: {
            try await runner.run(executable: executable, arguments: arguments, onOutputLine: onOutputLine)
        }, onCancel: {
            runner.cancel()
        })
    }

    private func run(
        executable: URL,
        arguments: [String],
        onOutputLine: @escaping @Sendable (String) -> Void
    ) async throws -> Output {
        try Task.checkCancellation()
        let standardOutput = Pipe()
        let standardError = Pipe()
        let output = BoundedPipe(limit: 1_048_576, lineHandler: onOutputLine)
        let error = BoundedPipe(limit: 65_536)
        standardOutput.fileHandleForReading.readabilityHandler = { output.consume($0.availableData) }
        standardError.fileHandleForReading.readabilityHandler = { error.consume($0.availableData) }

        return try await withCheckedThrowingContinuation { continuation in
            let task = Process()
            task.executableURL = executable
            task.arguments = arguments
            task.standardOutput = standardOutput
            task.standardError = standardError
            task.terminationHandler = { [weak self] process in
                standardOutput.fileHandleForReading.readabilityHandler = nil
                standardError.fileHandleForReading.readabilityHandler = nil
                output.consume(standardOutput.fileHandleForReading.readDataToEndOfFile())
                error.consume(standardError.fileHandleForReading.readDataToEndOfFile())
                if self?.wasCancelled == true {
                    continuation.resume(throwing: CancellationError())
                } else {
                    continuation.resume(returning: Output(status: process.terminationStatus, stdout: output.data, stderr: error.text, stdoutTruncated: output.wasTruncated))
                }
            }
            do {
                try setAndRun(task)
            } catch {
                standardOutput.fileHandleForReading.readabilityHandler = nil
                standardError.fileHandleForReading.readabilityHandler = nil
                continuation.resume(throwing: error)
            }
        }
    }

    private func setAndRun(_ task: Process) throws {
        lock.lock()
        let shouldCancel = cancelled
        process = task
        lock.unlock()
        if shouldCancel { throw CancellationError() }
        do {
            try task.run()
            // Cancellation can land after `isRunning` was checked in cancel() but before
            // Process.run() starts the child. Recheck after launch so that narrow race cannot
            // leave ffmpeg or ffprobe running after its parent Task has been cancelled.
            lock.lock(); let cancelAfterLaunch = cancelled; lock.unlock()
            if cancelAfterLaunch { task.terminate() }
        }
        catch {
            lock.lock(); process = nil; lock.unlock()
            throw error
        }
    }

    private var wasCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    private func cancel() {
        lock.lock(); cancelled = true; let active = process; lock.unlock()
        if active?.isRunning == true { active?.terminate() }
    }
}

private final class BoundedPipe: @unchecked Sendable {
    private let lock = NSLock()
    private let limit: Int
    private let lineHandler: @Sendable (String) -> Void
    private var storage = Data()
    private var lineBuffer = Data()
    private var truncated = false

    init(limit: Int, lineHandler: @escaping @Sendable (String) -> Void = { _ in }) {
        self.limit = limit
        self.lineHandler = lineHandler
    }

    func consume(_ next: Data) {
        guard !next.isEmpty else { return }
        var lines: [String] = []
        lock.lock()
        if storage.count < limit {
            let remaining = limit - storage.count
            storage.append(next.prefix(remaining))
            if next.count > remaining { truncated = true }
        } else {
            truncated = true
        }
        lineBuffer.append(next)
        while let newline = lineBuffer.firstIndex(of: 10) {
            let line = lineBuffer[..<newline]
            lineBuffer.removeSubrange(...newline)
            lines.append(String(decoding: line, as: UTF8.self).trimmingCharacters(in: .newlines))
        }
        // ffmpeg progress lines are tiny. Bound a malformed unterminated line separately.
        if lineBuffer.count > 8_192 { lineBuffer.removeAll(keepingCapacity: false) }
        lock.unlock()
        lines.forEach(lineHandler)
    }

    var data: Data { lock.lock(); defer { lock.unlock() }; return storage }
    var text: String { String(decoding: data, as: UTF8.self) }
    var wasTruncated: Bool { lock.lock(); defer { lock.unlock() }; return truncated }
}
