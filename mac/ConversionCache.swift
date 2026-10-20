import Foundation

/// A durable, app-wide policy for the MP4 files created by `VideoCompatibility`.
///
/// The cache deliberately owns only its SHA-256-named MP4 outputs and their incomplete
/// siblings. It never enumerates Photos, movie libraries, settings, credentials, or the
/// HDR conversion LUT that happens to live beside the cache outputs.
struct ConversionCacheSnapshot: Sendable, Equatable {
    let sizeBytes: Int64
    let fileCount: Int
    /// `nil` means no cap. This is the default to retain the app's existing behavior.
    let limitBytes: Int64?
    let activeConversionCount: Int
    let activeUseCount: Int
}

struct ConversionCacheClearResult: Sendable, Equatable {
    let removedBytes: Int64
    let removedCount: Int
    let skippedBytes: Int64
    let skippedCount: Int
}

struct ConversionCacheUseToken: Sendable, Hashable {
    fileprivate let id: UUID
}

struct ConversionCacheConversionTicket: Sendable, Hashable {
    fileprivate let id: UUID
    fileprivate let targetPath: String
}

enum ConversionCacheError: LocalizedError, Sendable {
    case limitReached(limitBytes: Int64)
    case invalidManagedURL

    var errorDescription: String? {
        switch self {
        case .limitReached:
            return "The video conversion cache has reached its storage limit. Clear cached conversions or increase the limit before preparing another video."
        case .invalidManagedURL:
            return "This file is not a managed video conversion cache entry."
        }
    }
}

/// Serializes cache accounting and deletion so an explicit clear never removes an output that
/// is converting or that a caller has leased for streaming. The owner of a stream must retain
/// its `ConversionCacheUseToken` until the stream stops.
actor ConversionCache {
    static let shared = ConversionCache()

    static let directory = AppPaths.applicationSupport.appendingPathComponent("Video Compatibility", isDirectory: true)

    private static let limitDefaultsKey = "videoCompatibilityCacheLimitBytes"
    private let fileManager: FileManager
    private let defaults: UserDefaults
    private var activeConversions: [UUID: String] = [:]
    private var activeUses: [UUID: String] = [:]

    init(fileManager: FileManager = .default, defaults: UserDefaults = .standard) {
        self.fileManager = fileManager
        self.defaults = defaults
    }

    func snapshot() -> ConversionCacheSnapshot {
        let entries = managedEntries()
        return ConversionCacheSnapshot(
            sizeBytes: entries.reduce(0) { $0 + $1.sizeBytes },
            fileCount: entries.count,
            limitBytes: storedLimitBytes,
            activeConversionCount: activeConversions.count,
            activeUseCount: activeUses.count
        )
    }

    /// A zero or `nil` value means unlimited. Lowering a limit never evicts existing cache
    /// files, because they may still be selected for a stream or an imported video.
    func setLimitBytes(_ value: Int64?) {
        let stored = max(0, value ?? 0)
        defaults.set(stored, forKey: Self.limitDefaultsKey)
    }

    /// Registers a target before ffmpeg starts. Clearing skips both the final target and any
    /// matching `.incomplete.mp4` file until `finishConversion(_:)` is called.
    func beginConversion(at target: URL) throws -> ConversionCacheConversionTicket {
        guard Self.isManagedFinalURL(target) else { throw ConversionCacheError.invalidManagedURL }
        if let limit = storedLimitBytes,
           managedEntries().reduce(Int64(0), { $0 + $1.sizeBytes }) >= limit {
            throw ConversionCacheError.limitReached(limitBytes: limit)
        }
        let ticket = ConversionCacheConversionTicket(id: UUID(), targetPath: target.standardizedFileURL.path)
        activeConversions[ticket.id] = ticket.targetPath
        return ticket
    }

    /// Checks the actual verified output immediately before its atomic publish. The conversion
    /// may run while another output completes, so this is the authoritative admission check.
    func admit(_ temporary: URL, for ticket: ConversionCacheConversionTicket) throws {
        guard activeConversions[ticket.id] == ticket.targetPath,
              Self.isManagedIncompleteURL(temporary, forTargetPath: ticket.targetPath),
              let outputSize = fileSize(at: temporary), outputSize > 0 else {
            throw ConversionCacheError.invalidManagedURL
        }
        guard let limit = storedLimitBytes else { return }
        let existingBytes = managedEntries(excludingPath: temporary.standardizedFileURL.path)
            .reduce(Int64(0)) { $0 + $1.sizeBytes }
        guard Self.admissionIsAllowed(existingBytes: existingBytes, outputBytes: outputSize, limitBytes: limit) else {
            throw ConversionCacheError.limitReached(limitBytes: limit)
        }
    }

    func finishConversion(_ ticket: ConversionCacheConversionTicket) {
        activeConversions.removeValue(forKey: ticket.id)
    }

    /// Keeps a managed output safe from an explicit clear while it is being streamed. Returns
    /// nil for direct source URLs, which are never owned by this cache.
    func acquireUse(for url: URL) -> ConversionCacheUseToken? {
        guard Self.isManagedFinalURL(url) else { return nil }
        let token = ConversionCacheUseToken(id: UUID())
        activeUses[token.id] = url.standardizedFileURL.path
        return token
    }

    func releaseUse(_ token: ConversionCacheUseToken) {
        activeUses.removeValue(forKey: token.id)
    }

    /// Deletes only idle managed cache outputs. Call this only after the UI has received the
    /// user's native confirmation. Existing cache entries are intentionally not evicted when a
    /// user merely changes the limit.
    func clearUserTriggered() -> ConversionCacheClearResult {
        // Converted ambient videos are stored in the cache but are also referenced by the local
        // media catalogue. They are retained so this action cannot break an imported library.
        let entries = managedEntries()
        guard let persistedReferences = persistedMediaReferencePaths() else {
            // A malformed or unavailable library record must never be interpreted as an empty
            // library. Retain every cache entry until the local records are readable again.
            return ConversionCacheClearResult(
                removedBytes: 0,
                removedCount: 0,
                skippedBytes: entries.reduce(0) { $0 + $1.sizeBytes },
                skippedCount: entries.count
            )
        }
        let protectedPaths = Set(activeConversions.values)
            .union(activeUses.values)
            .union(persistedReferences)
        var removedBytes: Int64 = 0
        var removedCount = 0
        var skippedBytes: Int64 = 0
        var skippedCount = 0

        for entry in entries {
            if Self.isProtected(entry.url, by: protectedPaths) {
                skippedBytes += entry.sizeBytes
                skippedCount += 1
                continue
            }
            do {
                try fileManager.removeItem(at: entry.url)
                removedBytes += entry.sizeBytes
                removedCount += 1
            } catch {
                // A file can disappear or become unavailable between enumeration and removal.
                // Leave it alone and report it as retained rather than risking a retry.
                skippedBytes += entry.sizeBytes
                skippedCount += 1
            }
        }
        return ConversionCacheClearResult(removedBytes: removedBytes, removedCount: removedCount, skippedBytes: skippedBytes, skippedCount: skippedCount)
    }

    private var storedLimitBytes: Int64? {
        let value = defaults.object(forKey: Self.limitDefaultsKey) as? NSNumber
        let bytes = value?.int64Value ?? 0
        return bytes > 0 ? bytes : nil
    }

    private struct Entry {
        let url: URL
        let sizeBytes: Int64
    }

    private func managedEntries(excludingPath: String? = nil) -> [Entry] {
        guard (try? fileManager.createDirectory(at: Self.directory, withIntermediateDirectories: true)) != nil,
              let files = try? fileManager.contentsOfDirectory(
                at: Self.directory,
                includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
                options: []
              ) else { return [] }
        return files.compactMap { url in
            guard url.standardizedFileURL.path != excludingPath,
                  Self.isManagedCacheURL(url),
                  let size = fileSize(at: url), size > 0 else { return nil }
            return Entry(url: url, sizeBytes: size)
        }
    }

    private func fileSize(at url: URL) -> Int64? {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true,
              let bytes = values.fileSize else { return nil }
        return Int64(bytes)
    }

    /// Read, but never rewrite, every supported local library record. The legacy document may
    /// coexist with per-TV profiles during migration, so both are considered before deleting.
    private func persistedMediaReferencePaths() -> Set<String>? {
        var photos: [LocalPhoto] = []
        if fileManager.fileExists(atPath: AppPaths.settings.path) {
            guard let data = try? Data(contentsOf: AppPaths.settings),
                  let settings = try? JSONDecoder().decode(LocalSettings.self, from: data) else { return nil }
            photos.append(contentsOf: settings.photos)
        }
        if fileManager.fileExists(atPath: AppPaths.tvProfiles.path) {
            guard let data = try? Data(contentsOf: AppPaths.tvProfiles),
                  let profiles = try? JSONDecoder().decode(TVProfiles.self, from: data) else { return nil }
            photos.append(contentsOf: profiles.sharedPhotos)
            photos.append(contentsOf: profiles.profiles.flatMap { $0.settings.photos })
            photos.append(contentsOf: profiles.pending?.settings.photos ?? [])
        }
        return Set(photos.compactMap(\.compatibleVideoURL).compactMap { url in
            Self.isManagedFinalURL(url) ? url.standardizedFileURL.path : nil
        })
    }

    private static func isProtected(_ url: URL, by paths: Set<String>) -> Bool {
        let path = url.standardizedFileURL.path
        if paths.contains(path) { return true }
        guard let hash = incompleteHash(in: url.lastPathComponent) else { return false }
        return paths.contains(directory.appendingPathComponent("\(hash).mp4").standardizedFileURL.path)
    }

    private static func isManagedCacheURL(_ url: URL) -> Bool {
        isManagedFinalURL(url) || incompleteHash(in: url.lastPathComponent) != nil
    }

    private static func isManagedFinalURL(_ url: URL) -> Bool {
        url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL
            && url.pathExtension.lowercased() == "mp4"
            && isSHA256Hex(url.deletingPathExtension().lastPathComponent)
    }

    private static func isManagedIncompleteURL(_ url: URL, forTargetPath targetPath: String) -> Bool {
        guard url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL,
              let hash = incompleteHash(in: url.lastPathComponent) else { return false }
        return directory.appendingPathComponent("\(hash).mp4").standardizedFileURL.path == targetPath
    }

    private static func incompleteHash(in name: String) -> String? {
        let parts = name.split(separator: ".", omittingEmptySubsequences: true)
        guard parts.count == 4,
              parts[2] == "incomplete",
              parts[3].lowercased() == "mp4",
              isSHA256Hex(String(parts[0])) else { return nil }
        return String(parts[0])
    }

    private static func isSHA256Hex(_ value: String) -> Bool {
        value.count == 64 && value.unicodeScalars.allSatisfy {
            (48...57).contains($0.value) || (97...102).contains($0.value)
        }
    }

    /// Pure regression checks for the cache filename boundary and conservative limit policy.
    /// They allocate no files and never inspect or remove user cache contents.
    static func safetyChecksPass() -> Bool {
        let hash = String(repeating: "a", count: 64)
        let valid = directory.appendingPathComponent("\(hash).mp4")
        let incomplete = directory.appendingPathComponent(".\(hash).ticket.incomplete.mp4")
        let outside = directory.deletingLastPathComponent().appendingPathComponent("Elsewhere").appendingPathComponent("\(hash).mp4")
        return isManagedFinalURL(valid)
            && isManagedCacheURL(incomplete)
            && !isManagedCacheURL(outside)
            && admissionIsAllowed(existingBytes: 80, outputBytes: 20, limitBytes: 100)
            && !admissionIsAllowed(existingBytes: 80, outputBytes: 21, limitBytes: 100)
            && !admissionIsAllowed(existingBytes: 100, outputBytes: 1, limitBytes: 100)
    }

    private static func admissionIsAllowed(existingBytes: Int64, outputBytes: Int64, limitBytes: Int64) -> Bool {
        existingBytes >= 0 && outputBytes > 0 && limitBytes > 0 && existingBytes <= limitBytes - outputBytes
    }
}
