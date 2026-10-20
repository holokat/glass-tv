import AppKit
import Foundation

@MainActor
final class MoviesModel: ObservableObject {
    @Published private(set) var state: MovieLibraryState
    @Published private(set) var isScanning = false
    @Published private(set) var metadataStatus = ""
    @Published var errorMessage: String?
    @Published var selectedID: String?
    @Published var playingStatus: MovieStatusSnapshot?
    @Published private(set) var preparationMessage = ""
    /// Changes only when shareable library data is rescanned or edited, never for playback progress.
    @Published private(set) var libraryRevision: UInt = 0

    private let store = MovieLibraryStore()
    private let metadataProvider = MovieMetadataProvider()
    private var scanTask: Task<Void, Never>?
    private var metadataTask: Task<Void, Never>?
    private var playTask: Task<Void, Never>?
    private var controlTask: Task<Void, Never>?
    private var pollingTask: Task<Void, Never>?
    private var sessionID: String?
    private var playbackGeneration: UInt = 0
    private var activity: NSObjectProtocol?
    private var scopedFolder: URL?
    private var bridgeRequestID: String?
    private var bridgeRequestFailure: (id: String, message: String)?

    init() {
        state = store.load()
        if state.folderBookmark != nil { refresh() }
    }

    deinit {
        scanTask?.cancel(); metadataTask?.cancel(); playTask?.cancel(); controlTask?.cancel(); pollingTask?.cancel()
        if let activity { ProcessInfo.processInfo.endActivity(activity) }
        scopedFolder?.stopAccessingSecurityScopedResource()
    }

    var movies: [MovieItem] { state.movies }
    var selectedMovie: MovieItem? { selectedID.flatMap { id in state.movies.first { $0.id == id } } }
    var folderName: String? { state.folderName }
    var hasFolder: Bool { state.folderBookmark != nil }
    func isFavorite(_ movie: MovieItem) -> Bool { state.favoriteIDs.contains(movie.id) }
    func isWatched(_ movie: MovieItem) -> Bool { state.watchedIDs.contains(movie.id) }

    func toggleFavorite(_ movie: MovieItem) {
        var updated = state
        if !updated.favoriteIDs.insert(movie.id).inserted { updated.favoriteIDs.remove(movie.id) }
        persistOrganization(updated)
    }

    func toggleWatched(_ movie: MovieItem) {
        var updated = state
        if !updated.watchedIDs.insert(movie.id).inserted { updated.watchedIDs.remove(movie.id) }
        persistOrganization(updated)
    }

    private func persistOrganization(_ updated: MovieLibraryState) {
        do {
            try store.save(updated)
            state = updated
            markLibraryChanged()
        } catch { errorMessage = "Could not save movie changes: \(error.localizedDescription)" }
    }

    func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = "Choose folder"
        guard panel.runModal() == .OK, let folder = panel.url else { return }
        guard (try? folder.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else {
            errorMessage = "Choose a folder rather than an individual video file."
            return
        }
        do {
            scopedFolder?.stopAccessingSecurityScopedResource()
            scopedFolder = nil
            state.folderBookmark = try store.bookmark(for: folder)
            state.folderName = folder.lastPathComponent
            state.movies = []
            state.progress = [:]
            state.favoriteIDs = []
            state.watchedIDs = []
            try save()
            markLibraryChanged()
            refresh()
        } catch { errorMessage = error.localizedDescription }
    }

    func refresh() {
        guard state.folderBookmark != nil else { return }
        scanTask?.cancel(); metadataTask?.cancel()
        do {
            let folder = try activeFolder()
            isScanning = true; errorMessage = nil
            let previous = Dictionary(uniqueKeysWithValues: state.movies.map { ($0.id, $0) })
            scanTask = Task {
                let scanned = await Task.detached(priority: .userInitiated) { MovieScanner.scan(folder: folder, previous: previous) }.value
                guard !Task.isCancelled else { return }
                state.movies = scanned
                markLibraryChanged()
                if selectedID == nil { selectedID = scanned.first?.id }
                isScanning = false
                try? save()
                fetchMetadataSerially()
            }
        } catch { errorMessage = error.localizedDescription; isScanning = false }
    }

    func fileURL(for movie: MovieItem) -> URL? {
        guard let folder = try? activeFolder() else { return nil }
        let url = folder.appendingPathComponent(movie.relativePath)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func posterURL(for movie: MovieItem) -> URL? {
        if let local = localFolderURL.flatMap({ MovieScanner.sidecarPosterURL(movie: movie, folder: $0) }) { return local }
        if let cached = movie.cachedPosterFileName {
            let url = MoviePosterCache.cachedURL(named: cached)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        if let frame = movie.frameFileName {
            let url = AppPaths.movieFrames.appendingPathComponent(frame)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        return nil
    }

    func progress(for movie: MovieItem) -> MovieProgress? { state.progress[movie.id] }

    /// Reports only the playback created for a TV library request. Manual playback never
    /// acquires this ownership, so the bridge cannot stop a user-initiated stream.
    func bridgePlaybackState(for requestID: String) -> MovieBridgePlaybackState {
        if let failure = bridgeRequestFailure, failure.id == requestID { return .failed(failure.message) }
        guard bridgeRequestID == requestID else { return .notOwned }
        guard let status = playingStatus else { return .notOwned }
        if !preparationMessage.isEmpty { return .preparing(preparationMessage) }
        switch status.state {
        case .preparing, .playing, .paused: return .started
        case .error: return .failed(status.error ?? "Movie playback failed.")
        default: return .notOwned
        }
    }

    func cancelBridgePreparation(requestID: String, companion: CompanionModel, streamServer: MovieHTTPServer) {
        guard bridgeRequestID == requestID, !preparationMessage.isEmpty else { return }
        stop(companion: companion, streamServer: streamServer)
    }

    func startPlayback(movie: MovieItem, companion: CompanionModel, streamServer: MovieHTTPServer, bridgeRequestID: String? = nil) {
        guard movie.decodeError == nil else { errorMessage = movie.decodeError; return }
        guard let file = fileURL(for: movie) else { errorMessage = "This movie file is no longer available."; return }
        guard companion.isConnected, let connection = companion.movieTVConnection() else {
            errorMessage = companion.isPaired ? "Connect to the TV before playing a movie." : "Pair with the TV before playing a movie."
            return
        }

        let session = UUID().uuidString.lowercased()
        let priorStart = playTask
        let generation = beginPlayback(session: session, movie: movie, streamServer: streamServer, bridgeRequestID: bridgeRequestID)
        playTask = Task { [weak self] in
            if let priorStart { await priorStart.value }
            guard let self, self.isCurrent(session: session, generation: generation), !Task.isCancelled else { return }

            do {
                let prepared = try await VideoCompatibility.compatibleURL(for: file) { [weak self] status in
                    Task { @MainActor [weak self] in
                        guard let self, self.isCurrent(session: session, generation: generation) else { return }
                        self.preparationMessage = Self.preparationText(status)
                    }
                }
                var cacheUseTransferred = false
                defer {
                    if !cacheUseTransferred, let cacheUse = prepared.cacheUse {
                        Task { await ConversionCache.shared.releaseUse(cacheUse) }
                    }
                }
                guard self.isCurrent(session: session, generation: generation), !Task.isCancelled else { return }
                let stream = try await streamServer.start(movieURL: prepared.url, cacheUse: prepared.cacheUse, allowedPeerIPv4: connection.host, sessionID: session, movieID: movie.id, title: movie.displayTitle)
                cacheUseTransferred = true
                guard self.isCurrent(session: session, generation: generation), !Task.isCancelled else {
                    streamServer.stop()
                    return
                }
                let payload = MoviePlayPayload(apiVersion: 1, sessionId: session, movieId: movie.id, title: movie.displayTitle,
                                               url: stream.url.absoluteString, token: stream.token, certificatePin: stream.certificatePin,
                                               resumePositionMs: progress(for: movie)?.positionMs ?? 0)
                do {
                    let status = try await connection.client.moviePlay(payload)
                    guard self.isCurrent(session: session, generation: generation), !Task.isCancelled else { return }
                    self.preparationMessage = ""
                    self.apply(status, expectedSession: session, generation: generation, streamServer: streamServer)
                    guard self.isCurrent(session: session, generation: generation) else { return }
                    self.beginPolling(client: connection.client, expectedSession: session, generation: generation, streamServer: streamServer)
                } catch {
                    guard self.isCurrent(session: session, generation: generation), !Task.isCancelled else { return }
                    self.finishPlayback(expectedSession: session, generation: generation, streamServer: streamServer, error: error.localizedDescription)
                }
            } catch {
                guard self.isCurrent(session: session, generation: generation), !Task.isCancelled else { return }
                self.finishPlayback(expectedSession: session, generation: generation, streamServer: streamServer, error: error.localizedDescription)
            }
        }
    }

    func control(_ action: MovieControlAction, positionMs: Int64? = nil, companion: CompanionModel, streamServer: MovieHTTPServer) {
        if action == .resume, !streamServer.isAvailable {
            let activeID = playingStatus?.movieId ?? selectedID
            guard let activeID, let movie = movies.first(where: { $0.id == activeID }) else {
                errorMessage = "The movie stream expired. Select the movie again to continue."
                return
            }
            // The ephemeral local stream cannot be resumed after its token expires. Starting a
            // new, pinned stream keeps the saved receiver position without reusing stale state.
            startPlayback(movie: movie, companion: companion, streamServer: streamServer)
            return
        }
        guard let session = sessionID, let connection = companion.movieTVConnection() else { return }
        let generation = playbackGeneration
        controlTask?.cancel()
        controlTask = Task { [weak self] in
            do {
                let status = try await connection.client.movieControl(MovieControlPayload(apiVersion: 1, sessionId: session, action: action, positionMs: positionMs))
                guard let self, self.isCurrent(session: session, generation: generation), !Task.isCancelled else { return }
                if self.errorMessage == "Checking movie playback connection…" { self.errorMessage = nil }
                self.apply(status, expectedSession: session, generation: generation, streamServer: streamServer)
            } catch {
                guard let self, self.isCurrent(session: session, generation: generation), !Task.isCancelled else { return }
                self.errorMessage = error.localizedDescription
            }
        }
    }

    func stop(companion: CompanionModel, streamServer: MovieHTTPServer) {
        guard let session = sessionID else { return }
        let connection = companion.movieTVConnection()
        invalidatePlayback(streamServer: streamServer)
        guard let connection else { return }
        Task {
            // This request is intentionally best-effort. Its result cannot affect a later session.
            _ = try? await connection.client.movieControl(MovieControlPayload(apiVersion: 1, sessionId: session, action: .stop, positionMs: nil))
        }
    }

    private func beginPolling(client: GlassTVClient, expectedSession: String, generation: UInt, streamServer: MovieHTTPServer) {
        pollingTask?.cancel()
        pollingTask = Task { [weak self] in
            var transientFailures = 0
            while let self, !Task.isCancelled, self.isCurrent(session: expectedSession, generation: generation) {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled, self.isCurrent(session: expectedSession, generation: generation) else { return }
                do {
                    let status = try await client.movieStatus()
                    guard self.isCurrent(session: expectedSession, generation: generation), !Task.isCancelled else { return }
                    if self.errorMessage == "Checking movie playback connection…" { self.errorMessage = nil }
                    self.apply(status, expectedSession: expectedSession, generation: generation, streamServer: streamServer)
                    transientFailures = 0
                } catch {
                    guard self.isCurrent(session: expectedSession, generation: generation), !Task.isCancelled else { return }
                    transientFailures += 1
                    guard transientFailures < 3 else {
                        self.finishPlayback(expectedSession: expectedSession, generation: generation, streamServer: streamServer, error: error.localizedDescription)
                        return
                    }
                    self.errorMessage = "Checking movie playback connection…"
                }
            }
        }
    }

    private func apply(_ status: MovieStatusSnapshot, expectedSession: String, generation: UInt, streamServer: MovieHTTPServer) {
        guard isCurrent(session: expectedSession, generation: generation),
              status.sessionId == nil || status.sessionId == expectedSession else { return }
        guard status.supported else {
            finishPlayback(expectedSession: expectedSession, generation: generation, streamServer: streamServer,
                           error: "This TV software does not support movie streaming yet.")
            return
        }
        playingStatus = status
        if let movieID = status.movieId, let position = status.positionMs, let duration = status.durationMs, duration > 0 {
            state.progress[movieID] = MovieProgress(positionMs: position, durationMs: duration, updatedAt: Date())
            try? save()
        }
        if status.state == .idle || status.state == .completed || status.state == .error {
            finishPlayback(expectedSession: expectedSession, generation: generation, streamServer: streamServer, error: status.error)
        } else if status.state == .paused {
            releaseWakeLock()
        } else if status.state == .preparing || status.state == .playing {
            acquireWakeLock()
        }
    }

    @discardableResult
    private func beginPlayback(session: String, movie: MovieItem, streamServer: MovieHTTPServer, bridgeRequestID: String?) -> UInt {
        invalidatePlayback(streamServer: streamServer)
        self.bridgeRequestID = bridgeRequestID
        bridgeRequestFailure = nil
        playbackGeneration &+= 1
        sessionID = session
        playingStatus = MovieStatusSnapshot(supported: true, sessionId: session, movieId: movie.id, title: movie.displayTitle,
                                            state: .preparing, positionMs: progress(for: movie)?.positionMs, durationMs: movie.durationMs,
                                            width: movie.width, height: movie.height, error: nil)
        preparationMessage = "Preparing video…"
        errorMessage = nil
        return playbackGeneration
    }

    private func finishPlayback(expectedSession: String, generation: UInt, streamServer: MovieHTTPServer, error: String?) {
        guard isCurrent(session: expectedSession, generation: generation) else { return }
        if let error, !error.isEmpty {
            errorMessage = error
            if let bridgeRequestID { bridgeRequestFailure = (bridgeRequestID, error) }
        }
        invalidatePlayback(streamServer: streamServer)
    }

    private func invalidatePlayback(streamServer: MovieHTTPServer) {
        playbackGeneration &+= 1
        playTask?.cancel(); playTask = nil
        controlTask?.cancel(); controlTask = nil
        pollingTask?.cancel(); pollingTask = nil
        bridgeRequestID = nil
        sessionID = nil
        playingStatus = nil
        preparationMessage = ""
        streamServer.stop()
        releaseWakeLock()
    }

    private func isCurrent(session: String, generation: UInt) -> Bool {
        sessionID == session && playbackGeneration == generation
    }

    private static func preparationText(_ status: VideoCompatibilityStatus) -> String {
        switch status {
        case .probing: return "Checking video…"
        case .direct: return "Preparing secure stream…"
        case .converting(let fraction):
            guard let fraction else { return "Converting video for TV…" }
            return "Converting video for TV… \(Int((fraction * 100).rounded()))%"
        case .verifying: return "Verifying converted video…"
        case .completed: return "Preparing secure stream…"
        }
    }

    private func fetchMetadataSerially() {
        metadataTask?.cancel()
        metadataTask = Task {
            let ids = state.movies.filter { $0.metadata == nil }.map(\.id)
            for id in ids {
                guard !Task.isCancelled, let index = state.movies.firstIndex(where: { $0.id == id }) else { return }
                let movie = state.movies[index]
                metadataStatus = "Looking up \(movie.title)…"
                do {
                    if let metadata = try await metadataProvider.lookup(title: movie.title, year: movie.year) {
                        state.movies[index].metadata = metadata
                        markLibraryChanged()
                        if let posterURL = metadata.posterURL, let cached = await MoviePosterCache.download(posterURL, id: movie.id) {
                            state.movies[index].cachedPosterFileName = cached
                        }
                        try? save()
                    }
                } catch is CancellationError { return }
                catch { /* Offline or unmatched metadata is intentionally retried on a later refresh. */ }
            }
            metadataStatus = ""
        }
    }

    private var localFolderURL: URL? {
        try? activeFolder()
    }
    private func activeFolder() throws -> URL {
        if let scopedFolder { return scopedFolder }
        guard let bookmark = state.folderBookmark else { throw MovieLibraryError.noFolder }
        let folder = try store.resolveFolder(bookmark: bookmark)
        guard folder.startAccessingSecurityScopedResource() else { throw MovieLibraryError.bookmarkUnavailable }
        scopedFolder = folder
        return folder
    }
    private func save() throws { try store.save(state) }
    private func markLibraryChanged() { libraryRevision &+= 1 }
    private func acquireWakeLock() {
        guard activity == nil else { return }
        activity = ProcessInfo.processInfo.beginActivity(options: .idleSystemSleepDisabled, reason: "Glass TV movie stream")
    }
    private func releaseWakeLock() { if let activity { ProcessInfo.processInfo.endActivity(activity); self.activity = nil } }
}
