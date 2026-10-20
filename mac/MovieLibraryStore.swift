import AppKit
import Foundation

final class MovieLibraryStore {
    func load() -> MovieLibraryState {
        guard let data = try? Data(contentsOf: AppPaths.movies),
              let state = try? JSONDecoder().decode(MovieLibraryState.self, from: data) else { return MovieLibraryState() }
        return state
    }

    func save(_ state: MovieLibraryState) throws {
        let data = try JSONEncoder().encode(state)
        try data.write(to: AppPaths.movies, options: .atomic)
    }

    func resolveFolder(bookmark: Data) throws -> URL {
        var stale = false
        let url = try URL(resolvingBookmarkData: bookmark, options: [.withSecurityScope], relativeTo: nil, bookmarkDataIsStale: &stale)
        guard !stale else { throw MovieLibraryError.bookmarkUnavailable }
        return url
    }

    func bookmark(for folder: URL) throws -> Data {
        try folder.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: nil, relativeTo: nil)
    }
}

enum MovieScanner {
    static func scan(folder: URL, previous: [String: MovieItem]) -> [MovieItem] {
        let keys: Set<URLResourceKey> = [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .isPackageKey, .isHiddenKey, .fileSizeKey, .contentModificationDateKey]
        guard let enumerator = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles]) else { return [] }
        var movies: [MovieItem] = []
        while let url = enumerator.nextObject() as? URL {
            let values = try? url.resourceValues(forKeys: keys)
            if values?.isSymbolicLink == true || values?.isHidden == true {
                if values?.isDirectory == true { enumerator.skipDescendants() }
                continue
            }
            if values?.isPackage == true { enumerator.skipDescendants(); continue }
            guard values?.isRegularFile == true, MovieFileKind.shouldProbe(url) else { continue }
            guard let relative = relativePath(url, to: folder) else { continue }
            let id = movieStableID(relativePath: relative)
            let parsed = movieTitleAndYear(from: url.lastPathComponent)
            let sidecar = poster(for: url, folder: folder)
            let existing = previous[id]
            let size = Int64(values?.fileSize ?? 0)
            let modified = values?.contentModificationDate
            let unchanged = existing?.fileSize == size && existing?.modifiedAt == modified
            let probe = MovieProbe.probe(url)
            // A familiar video extension remains visible with its precise corruption error.
            // An unfamiliar suffix is only kept when ffprobe proves it has a video stream.
            guard MovieFileKind.accepts(url) || probe.error == nil else { continue }
            let frameName: String?
            if sidecar == nil, probe.error == nil {
                frameName = unchanged && existing?.frameFileName?.hasSuffix("-sdr-v1.jpg") == true ? existing?.frameFileName : MovieFrameExtractor.extract(from: url, id: id, durationMs: probe.durationMs, colorTransfer: probe.colorTransfer)
            } else {
                frameName = nil
            }
            movies.append(MovieItem(
                id: id,
                relativePath: relative,
                fileName: url.lastPathComponent,
                title: parsed.title,
                year: parsed.year,
                durationMs: probe.durationMs,
                width: probe.width,
                height: probe.height,
                fileSize: size,
                modifiedAt: modified,
                posterRelativePath: sidecar.flatMap { relativePath($0, to: folder) },
                frameFileName: frameName,
                cachedPosterFileName: unchanged ? existing?.cachedPosterFileName : nil,
                decodeError: probe.error,
                metadata: unchanged ? existing?.metadata : nil
            ))
        }
        return movies.sorted { $0.displayTitle.localizedCaseInsensitiveCompare($1.displayTitle) == .orderedAscending }
    }

    static func sidecarPosterURL(movie: MovieItem, folder: URL) -> URL? {
        guard let path = movie.posterRelativePath else { return nil }
        let url = folder.appendingPathComponent(path)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private static func poster(for video: URL, folder: URL) -> URL? {
        let stem = video.deletingPathExtension()
        for suffix in [".jpg", "-poster.jpg"] {
            let candidate = URL(fileURLWithPath: stem.path + suffix)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        let siblings = (try? FileManager.default.contentsOfDirectory(at: video.deletingLastPathComponent(), includingPropertiesForKeys: nil)) ?? []
        let movieCount = siblings.filter(MovieFileKind.accepts).count
        if movieCount == 1 {
            for name in ["poster.jpg", "cover.jpg"] {
                let candidate = video.deletingLastPathComponent().appendingPathComponent(name)
                if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            }
        }
        return nil
    }

    private static func relativePath(_ url: URL, to root: URL) -> String? {
        let rootPath = root.standardizedFileURL.path.hasSuffix("/") ? root.standardizedFileURL.path : root.standardizedFileURL.path + "/"
        let path = url.standardizedFileURL.path
        guard path.hasPrefix(rootPath) else { return nil }
        return String(path.dropFirst(rootPath.count))
    }
}

enum MoviePosterCache {
    static func cachedURL(named name: String) -> URL { AppPaths.movieFrames.appendingPathComponent(name) }
    static func download(_ url: URL, id: String) async -> String? {
        var request = URLRequest(url: url); request.timeoutInterval = 30; request.setValue("image/*", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode), data.count > 0, data.count <= 12 * 1024 * 1024,
              NSImage(data: data) != nil else { return nil }
        let currentBytes = ((try? FileManager.default.contentsOfDirectory(at: AppPaths.movieFrames, includingPropertiesForKeys: [.fileSizeKey])) ?? []).reduce(Int64(0)) { total, file in
            total + Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        guard currentBytes + Int64(data.count) <= 100 * 1024 * 1024 else { return nil }
        let name = "\(id)-poster.jpg", temporary = cachedURL(named: "\(id)-poster.tmp")
        let target = cachedURL(named: name)
        do {
            try data.write(to: temporary, options: .atomic)
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: temporary, to: target)
            return name
        } catch { try? FileManager.default.removeItem(at: temporary); return nil }
    }
}

private enum MovieFrameExtractor {
    static func extract(from source: URL, id: String, durationMs: Int64?, colorTransfer: String?) -> String? {
        guard let ffmpeg = MediaToolLocator.executable(named: "ffmpeg") else { return nil }
        let name = "\(id)-sdr-v1.jpg", target = AppPaths.movieFrames.appendingPathComponent(name)
        let temporary = AppPaths.movieFrames.appendingPathComponent("\(id).tmp.jpg")
        try? FileManager.default.removeItem(at: temporary)
        let seconds = max(1, min(30, Int((durationMs ?? 10_000) / 1_000 / 10)))
        let process = Process(); process.executableURL = ffmpeg
        var filter = "scale=min(960\\,iw):-2"
        if let transfer = colorTransfer, ["smpte2084", "arib-std-b67"].contains(transfer),
           let lut = try? VideoCompatibility.hdrToSDRLUT(transfer: transfer) {
            filter += ",format=gbrpf32le,lut3d=file='\(lut.path.replacingOccurrences(of: "'", with: "\\'"))',colorspace=iall=bt709:all=bt709:format=yuv420p:fast=1"
        }
        process.arguments = ["-nostdin", "-v", "error", "-ss", "\(seconds)", "-i", source.path, "-frames:v", "1", "-vf", filter, "-q:v", "4", temporary.path]
        process.standardOutput = Pipe(); process.standardError = Pipe()
        let finished = DispatchSemaphore(value: 0); process.terminationHandler = { _ in finished.signal() }
        guard (try? process.run()) != nil, finished.wait(timeout: .now() + 20) == .success,
              process.terminationStatus == 0, FileManager.default.fileExists(atPath: temporary.path) else { process.terminate(); return nil }
        try? FileManager.default.removeItem(at: target)
        do { try FileManager.default.moveItem(at: temporary, to: target); return name }
        catch { try? FileManager.default.removeItem(at: temporary); return nil }
    }
}

private enum MovieProbe {
    struct Result { var durationMs: Int64?; var width: Int?; var height: Int?; var colorTransfer: String?; var error: String? }

    static func probe(_ url: URL) -> Result {
        guard let ffprobe = MediaToolLocator.executable(named: "ffprobe") else { return Result() }
        let process = Process()
        process.executableURL = ffprobe
        process.arguments = ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,width,height,color_transfer:format=duration", "-of", "json", url.path]
        let pipe = Pipe(); process.standardOutput = pipe; process.standardError = Pipe()
        let finished = DispatchSemaphore(value: 0)
        process.terminationHandler = { _ in finished.signal() }
        do { try process.run() } catch { return Result(error: "Could not inspect this video.") }
        guard finished.wait(timeout: .now() + 12) == .success else { process.terminate(); return Result(error: "Video inspection timed out.") }
        guard process.terminationStatus == 0,
              let object = try? JSONSerialization.jsonObject(with: pipe.fileHandleForReading.readDataToEndOfFile()) as? [String: Any],
              let streams = object["streams"] as? [[String: Any]], let stream = streams.first else { return Result(error: "This video could not be decoded.") }
        let width = stream["width"] as? Int; let height = stream["height"] as? Int
        let duration = ((object["format"] as? [String: Any])?["duration"] as? String).flatMap(Double.init).map { Int64($0 * 1000) }
        // Compatibility is resolved only when a person starts playback. A file with a valid
        // video stream remains available even if it needs a derived H.264/AAC copy.
        return Result(durationMs: duration, width: width, height: height, colorTransfer: stream["color_transfer"] as? String, error: nil)
    }
}
