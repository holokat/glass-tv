import Foundation
import AppKit
import ImageIO
import AVFoundation
import CryptoKit

enum ImageProcessorError: LocalizedError {
    case unreadable, encodingFailed, tooLarge
    var errorDescription: String? {
        switch self { case .unreadable: return "This image could not be read."; case .encodingFailed: return "This image could not be converted to JPEG."; case .tooLarge: return "The converted image could not meet the TV size limit." }
    }
}

enum ImageProcessor {
    static let maxPixels = 8_294_400
    static let maxLongEdge = 3_840
    static let maxBytes = 8 * 1_024 * 1_024

    static func isImageFile(_ source: URL) -> Bool {
        guard let image = CGImageSourceCreateWithURL(source as CFURL, nil),
              CGImageSourceGetCount(image) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber else { return false }
        return width.intValue > 0 && height.intValue > 0
    }

    static func importImage(from source: URL) throws -> LocalPhoto {
        guard let sourceImage = CGImageSourceCreateWithURL(source as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(sourceImage, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? CGFloat,
              let height = properties[kCGImagePropertyPixelHeight] as? CGFloat else { throw ImageProcessorError.unreadable }
        let long = max(width, height), pixels = width * height
        var scale = min(CGFloat(maxLongEdge) / long, sqrt(CGFloat(maxPixels) / pixels), 1)
        var finalData: Data?
        for _ in 0..<5 {
            let target = max(1, Int(long * scale))
            let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                             kCGImageSourceCreateThumbnailWithTransform: true,
                                             kCGImageSourceThumbnailMaxPixelSize: target,
                                             kCGImageSourceShouldCacheImmediately: true]
            guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(sourceImage, 0, options as CFDictionary) else { throw ImageProcessorError.unreadable }
            let bitmap = NSBitmapImageRep(cgImage: thumbnail)
            let quality: CGFloat = finalData == nil ? 0.86 : 0.68
            guard let jpeg = bitmap.representation(using: .jpeg, properties: [.compressionFactor: quality]) else { throw ImageProcessorError.encodingFailed }
            if jpeg.count < maxBytes { finalData = jpeg; break }
            scale *= 0.78
        }
        guard let data = finalData else { throw ImageProcessorError.tooLarge }
        let digest = sha256Hex(data)
        let fileName = "\(digest).jpg"
        let destination = AppPaths.photos.appendingPathComponent(fileName)
        if !FileManager.default.fileExists(atPath: destination.path) { try data.write(to: destination, options: .atomic) }
        let cleanName = source.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        return LocalPhoto(uuid: UUID(), digest: digest, name: cleanName.isEmpty ? "Photo" : cleanName, fileName: fileName, addedAt: Date(), inRotation: true)
    }

    static func url(for photo: LocalPhoto) -> URL {
        if DefaultBackground.isBundled(photo), let bundled = DefaultBackground.bundledURL { return bundled }
        if photo.mediaKind == .video, let compatible = photo.compatibleVideoURL { return compatible }
        return AppPaths.photos.appendingPathComponent(photo.fileName)
    }
}

enum VideoProcessorError: LocalizedError {
    case noVideoTrack, tooLarge, insufficientDisk, copyFailed, thumbnailFailed
    var errorDescription: String? {
        switch self {
        case .noVideoTrack: return "This file does not contain a video track."
        case .tooLarge: return "The prepared video is larger than the TV’s 1 GB limit."
        case .insufficientDisk: return "This Mac needs more free space to copy this video safely."
        case .copyFailed: return "This video could not be copied."
        case .thumbnailFailed: return "This video could not be previewed."
        }
    }
}

enum VideoProcessor {
    static let maxBytes = 1_073_741_824
    static let diskReserve = 256 * 1_024 * 1_024

    struct ImportResult: Sendable {
        let photo: LocalPhoto
        /// Ownership transfers to `CompanionModel`, which releases it only after persisting the
        /// corresponding LocalPhoto reference.
        let cacheUse: ConversionCacheUseToken?
    }

    static func importVideo(
        from source: URL,
        progress: @escaping @Sendable (VideoCompatibilityStatus) -> Void = { _ in }
    ) async throws -> ImportResult {
        let compatible = try await VideoCompatibility.compatibleURL(for: source, progress: progress)
        let prepared = compatible.url
        let cacheUse = compatible.cacheUse
        var transferred = false
        defer {
            if !transferred, let cacheUse { Task { await ConversionCache.shared.releaseUse(cacheUse) } }
        }
        let values = try prepared.resourceValues(forKeys: [.fileSizeKey])
        let preparedBytes = values.fileSize ?? 0
        guard preparedBytes > 0, preparedBytes <= maxBytes else { throw VideoProcessorError.tooLarge }

        let asset = AVURLAsset(url: prepared)
        guard !(try await asset.loadTracks(withMediaType: .video)).isEmpty else { throw VideoProcessorError.noVideoTrack }
        let seconds = CMTimeGetSeconds(try await asset.load(.duration))
        guard seconds.isFinite, seconds > 0 else { throw VideoProcessorError.noVideoTrack }

        let digest: String
        let managedURL: URL?
        let fileName: String
        if compatible.wasTranscoded {
            // The shared compatibility cache is already Application Support-owned. Reuse it for
            // ambient media instead of creating a second potentially large managed copy.
            digest = try hashFile(prepared)
            managedURL = prepared
            fileName = prepared.lastPathComponent
        } else {
            let available = try AppPaths.applicationSupport.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
            guard available >= Int64(preparedBytes) + Int64(diskReserve) else { throw VideoProcessorError.insufficientDisk }
            let temporary = AppPaths.photos.appendingPathComponent(".\(UUID().uuidString).upload")
            digest = try copyAndHash(source: prepared, destination: temporary)
            fileName = "\(digest).mp4"
            let destination = AppPaths.photos.appendingPathComponent(fileName)
            if FileManager.default.fileExists(atPath: destination.path) { try? FileManager.default.removeItem(at: temporary) }
            else { try FileManager.default.moveItem(at: temporary, to: destination) }
            managedURL = nil
        }
        let previewName = "\(digest).preview.jpg"
        try writeThumbnail(asset: asset, durationSeconds: seconds, to: AppPaths.photos.appendingPathComponent(previewName))
        let name = source.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        let photo = LocalPhoto(uuid: UUID(), digest: digest, name: name.isEmpty ? "Video" : name, fileName: fileName, addedAt: Date(), inRotation: true, kind: .video, durationSeconds: seconds, byteCount: preparedBytes, previewFileName: previewName, compatibleVideoURL: managedURL)
        transferred = true
        return ImportResult(photo: photo, cacheUse: cacheUse)
    }

    private static func copyAndHash(source: URL, destination: URL) throws -> String {
        guard let input = InputStream(url: source) else { throw VideoProcessorError.copyFailed }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        guard let output = try? FileHandle(forWritingTo: destination) else { throw VideoProcessorError.copyFailed }
        defer { input.close(); try? output.close() }
        input.open()
        var hasher = SHA256()
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 1_048_576)
        defer { buffer.deallocate() }
        while true {
            let count = input.read(buffer, maxLength: 1_048_576)
            if count < 0 { throw VideoProcessorError.copyFailed }
            if count == 0 { break }
            let data = Data(bytes: buffer, count: count)
            hasher.update(data: data)
            try output.write(contentsOf: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func hashFile(_ source: URL) throws -> String {
        guard let input = InputStream(url: source) else { throw VideoProcessorError.copyFailed }
        defer { input.close() }
        input.open()
        var hasher = SHA256()
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 1_048_576)
        defer { buffer.deallocate() }
        while true {
            let count = input.read(buffer, maxLength: 1_048_576)
            if count < 0 { throw VideoProcessorError.copyFailed }
            if count == 0 { break }
            hasher.update(data: Data(bytes: buffer, count: count))
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func writeThumbnail(asset: AVAsset, durationSeconds: Double, to destination: URL) throws {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 900, height: 600)
        let previewTime = CMTime(seconds: min(30, max(0, durationSeconds / 3)), preferredTimescale: 600)
        guard let frame = (try? generator.copyCGImage(at: previewTime, actualTime: nil)) ?? (try? generator.copyCGImage(at: .zero, actualTime: nil)),
              let jpeg = NSBitmapImageRep(cgImage: frame).representation(using: .jpeg, properties: [.compressionFactor: 0.8]) else { throw VideoProcessorError.thumbnailFailed }
        try jpeg.write(to: destination, options: .atomic)
    }

    static func previewURL(for item: LocalPhoto) -> URL {
        AppPaths.photos.appendingPathComponent(item.previewFileName ?? item.fileName)
    }
}
