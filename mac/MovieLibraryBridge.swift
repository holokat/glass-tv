import Foundation
import ImageIO
import CoreGraphics
import Combine

struct MovieLibraryItemPayload: Codable, Equatable {
    let id: String
    let title: String
    let year: Int?
    let durationMs: Int64?
    let favorite: Bool
    let watched: Bool
    let playable: Bool
    let posterId: String?
}

struct MovieLibraryPayload: Codable, Equatable {
    let apiVersion: Int
    let publisherId: String
    let libraryId: String
    let items: [MovieLibraryItemPayload]
}

struct MovieLibraryReply: Codable { let supported: Bool }

struct MovieLibraryAck: Codable, Equatable {
    let requestId: String
    let state: String
    let message: String?
}

struct MovieLibraryPollPayload: Codable {
    let apiVersion: Int
    let publisherId: String
    let libraryId: String
    let ack: MovieLibraryAck?
}

struct MovieLibraryRequest: Codable {
    let requestId: String
    let movieId: String
    let state: String
}

struct MovieLibraryPollReply: Codable {
    let supported: Bool
    let request: MovieLibraryRequest?
}

/// A generation can publish only if no newer scan, metadata change, or preference
/// change occurred while its asynchronous work was in flight. A blocked generation
/// never renews a receiver lease for the previous catalogue.
struct MovieLibrarySyncState {
    private(set) var generation: UInt = 0
    private var publishedGeneration: UInt?
    private var blockedGeneration: UInt?

    var shouldSynchronize: Bool { publishedGeneration != generation && blockedGeneration != generation }
    var mayPoll: Bool { publishedGeneration == generation && blockedGeneration != generation }

    mutating func markDirty() { generation &+= 1 }

    @discardableResult
    mutating func markPublished(_ capturedGeneration: UInt) -> Bool {
        guard generation == capturedGeneration else { return false }
        publishedGeneration = capturedGeneration
        blockedGeneration = nil
        return true
    }

    @discardableResult
    mutating func block(_ capturedGeneration: UInt) -> Bool {
        guard generation == capturedGeneration else { return false }
        blockedGeneration = capturedGeneration
        return true
    }
}

struct MovieLibraryConnectionSession {
    private(set) var identity: String?

    mutating func update(_ nextIdentity: String) -> Bool {
        guard identity != nextIdentity else { return false }
        identity = nextIdentity
        return true
    }

    @discardableResult
    mutating func clear() -> Bool {
        guard identity != nil else { return false }
        identity = nil
        return true
    }
}

@MainActor
final class MovieLibraryBridge: ObservableObject {
    @Published private(set) var statusMessage = "Movies are off."

    private struct Context {
        let dirtyGeneration: UInt
        let lifecycleGeneration: UInt
        let libraryID: String
        let connectionID: String
    }

    private weak var movies: MoviesModel?
    private weak var companion: CompanionModel?
    private weak var streamServer: MovieHTTPServer?
    // A new authenticated session must replace the receiver’s publisher lease and any queued request.
    private var publisherID = UUID().uuidString.lowercased()
    private var worker: Task<Void, Never>?
    private var isReady = false
    private var connectionSession = MovieLibraryConnectionSession()
    private var lifecycleGeneration: UInt = 0
    private var syncState = MovieLibrarySyncState()
    private var uploadedPosterIDs = Set<String>()
    private var lastPublishedFingerprint: String?
    private var handledRequestIDs = Set<String>()
    private var activeRequestID: String?
    private var pendingAck: MovieLibraryAck?

    deinit { worker?.cancel() }

    func start(movies: MoviesModel, companion: CompanionModel, streamServer: MovieHTTPServer) {
        self.movies = movies
        self.companion = companion
        self.streamServer = streamServer
        lifecycleGeneration &+= 1
        if worker == nil {
            worker = Task { [weak self] in
                while !Task.isCancelled {
                    await self?.tick()
                    do { try await Task.sleep(nanoseconds: 2_000_000_000) }
                    catch { return }
                }
            }
        }
    }

    func libraryDidChange() { syncState.markDirty() }

    func availabilityDidChange() {
        lifecycleGeneration &+= 1
        guard let movies, let companion, let streamServer else { return }
        if !(companion.apps.moviesEnabled && companion.isConnected) {
            invalidateSession(movies: movies, companion: companion, streamServer: streamServer)
        }
    }

    private func tick() async {
        guard let movies, let companion, let streamServer else { return }
        let readyNow = companion.apps.moviesEnabled && companion.isConnected
        guard readyNow, let initialConnection = companion.movieTVConnection() else {
            invalidateSession(movies: movies, companion: companion, streamServer: streamServer)
            statusMessage = companion.apps.moviesEnabled
                ? (companion.isPaired ? "Connect to the TV to share movies." : "Pair with the TV to share movies.")
                : "Movies are off."
            return
        }
        let identity = connectionIdentity(initialConnection)
        let connectionChanged = connectionSession.update(identity)
        if !isReady || connectionChanged {
            if isReady, let requestID = activeRequestID {
                movies.cancelBridgePreparation(requestID: requestID, companion: companion, streamServer: streamServer)
            }
            isReady = true
            lifecycleGeneration &+= 1
            publisherID = UUID().uuidString.lowercased()
            activeRequestID = nil
            pendingAck = nil
            uploadedPosterIDs.removeAll()
            lastPublishedFingerprint = nil
            syncState.markDirty()
        }
        guard let context = currentContext() else { return }

        if syncState.shouldSynchronize { await synchronize(movies: movies, client: initialConnection.client, context: context) }
        guard isCurrent(context), syncState.mayPoll, let connection = companion.movieTVConnection() else { return }
        await poll(movies: movies, companion: companion, streamServer: streamServer, client: connection.client, context: context)
    }

    private func synchronize(movies: MoviesModel, client: GlassTVClient, context: Context) async {
        let listed = movies.movies
        guard listed.count <= 1_000 else {
            if syncState.block(context.dirtyGeneration) {
                statusMessage = "Movie sharing supports up to 1,000 movies. Choose a smaller folder."
            }
            return
        }

        let candidates = listed.compactMap { movie -> MovieLibraryPoster.Input? in
            movies.posterURL(for: movie).map { MovieLibraryPoster.Input(movieID: movie.id, sourceURL: $0) }
        }
        let posterResults = await MovieLibraryPoster.thumbnails(for: candidates)
        guard isCurrent(context) else { return }
        let postersByMovieID = Dictionary(uniqueKeysWithValues: posterResults.map { ($0.movieID, $0) })
        let items = listed.map { movie -> MovieLibraryItemPayload in
            let title = movie.displayTitle.trimmingCharacters(in: .whitespacesAndNewlines)
            return MovieLibraryItemPayload(
                id: movie.id,
                title: String((title.isEmpty ? (movie.fileName.isEmpty ? "Untitled movie" : movie.fileName) : title).prefix(240)),
                year: movie.displayYear,
                durationMs: movie.durationMs.map { max(0, $0) },
                favorite: movies.isFavorite(movie),
                watched: movies.isWatched(movie),
                playable: movie.decodeError == nil && movies.fileURL(for: movie) != nil,
                posterId: postersByMovieID[movie.id]?.id
            )
        }
        let payload = MovieLibraryPayload(apiVersion: 1, publisherId: publisherID, libraryId: context.libraryID, items: items)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let encoded = try? encoder.encode(payload), encoded.count <= 512 * 1024 else {
            if isCurrent(context), syncState.block(context.dirtyGeneration) {
                statusMessage = "Movie library is too large to share with the TV."
            }
            return
        }
        let fingerprint = sha256Hex(encoded)
        if fingerprint == lastPublishedFingerprint, posterResults.allSatisfy({ uploadedPosterIDs.contains($0.id) }) {
            guard isCurrent(context), syncState.markPublished(context.dirtyGeneration) else { return }
            statusMessage = listed.isEmpty ? "Choose a movie folder to share films." : "Movies are available on this TV."
            return
        }

        statusMessage = listed.isEmpty ? "Sharing an empty movie library." : "Sharing \(listed.count) \(listed.count == 1 ? "movie" : "movies")…"
        do {
            for poster in posterResults where !uploadedPosterIDs.contains(poster.id) {
                let reply = try await client.uploadMovieLibraryPoster(jpeg: poster.data, id: poster.id)
                guard isCurrent(context) else { return }
                guard reply.supported else {
                    if syncState.block(context.dirtyGeneration) { statusMessage = "Update the TV app to share movie artwork." }
                    return
                }
                uploadedPosterIDs.insert(poster.id)
            }
            let reply = try await client.putMovieLibrary(payload)
            guard isCurrent(context) else { return }
            guard reply.supported else {
                if syncState.block(context.dirtyGeneration) { statusMessage = "Update the TV app to share movies." }
                return
            }
            guard syncState.markPublished(context.dirtyGeneration) else { return }
            lastPublishedFingerprint = fingerprint
            statusMessage = listed.isEmpty ? "Choose a movie folder to share films." : "Movies are available on this TV."
        } catch {
            guard isCurrent(context) else { return }
            statusMessage = "Movies not shared: \(error.localizedDescription)"
        }
    }

    private func poll(movies: MoviesModel, companion: CompanionModel, streamServer: MovieHTTPServer, client: GlassTVClient, context: Context) async {
        let outgoingAck = pendingAck
        do {
            let reply = try await client.pollMovieLibrary(MovieLibraryPollPayload(
                apiVersion: 1, publisherId: publisherID, libraryId: context.libraryID, ack: outgoingAck
            ))
            guard isCurrent(context) else { return }
            guard reply.supported else {
                if syncState.block(context.dirtyGeneration) { statusMessage = "Update the TV app to receive movie requests." }
                return
            }
            if let outgoingAck {
                pendingAck = nil
                if outgoingAck.state == "started" || outgoingAck.state == "error" { activeRequestID = nil }
            }
            handle(reply.request, movies: movies, companion: companion, streamServer: streamServer, context: context)
            updateActiveRequest(movies: movies, context: context)
        } catch let error as GlassTVClientError {
            guard isCurrent(context) else { return }
            if case .unexpectedResponse(409, _) = error {
                // A receiver replacement never has our poster/session state. Force a full
                // upload rather than allowing an equal fingerprint to short-circuit it.
                uploadedPosterIDs.removeAll()
                lastPublishedFingerprint = nil
                syncState.markDirty()
                return
            }
            statusMessage = "Movie connection interrupted: \(error.localizedDescription)"
        } catch {
            guard isCurrent(context) else { return }
            statusMessage = "Movie connection interrupted: \(error.localizedDescription)"
        }
    }

    private func handle(_ request: MovieLibraryRequest?, movies: MoviesModel, companion: CompanionModel, streamServer: MovieHTTPServer, context: Context) {
        guard isCurrent(context), let request, isValidRequest(request) else { return }
        if request.state == "cancelled" {
            if activeRequestID == request.requestId {
                movies.cancelBridgePreparation(requestID: request.requestId, companion: companion, streamServer: streamServer)
                activeRequestID = nil
                pendingAck = nil
            }
            handledRequestIDs.insert(request.requestId)
            return
        }
        guard request.state == "queued", !handledRequestIDs.contains(request.requestId) else { return }
        handledRequestIDs.insert(request.requestId)
        guard let movie = movies.movies.first(where: { $0.id == request.movieId }), movie.decodeError == nil, movies.fileURL(for: movie) != nil else {
            activeRequestID = request.requestId
            pendingAck = acknowledgement(requestID: request.requestId, state: "error", message: "This movie is no longer available on the Mac.")
            return
        }
        guard isCurrent(context) else { return }
        activeRequestID = request.requestId
        movies.startPlayback(movie: movie, companion: companion, streamServer: streamServer, bridgeRequestID: request.requestId)
        pendingAck = acknowledgement(requestID: request.requestId, state: "preparing", message: movies.preparationMessage)
        statusMessage = "Preparing \(movie.displayTitle) for the TV…"
    }

    private func updateActiveRequest(movies: MoviesModel, context: Context) {
        guard isCurrent(context), let requestID = activeRequestID else { return }
        switch movies.bridgePlaybackState(for: requestID) {
        case .preparing(let message): pendingAck = acknowledgement(requestID: requestID, state: "preparing", message: message)
        case .started:
            pendingAck = acknowledgement(requestID: requestID, state: "started", message: nil)
            statusMessage = "Movie playback started on the TV."
        case .failed(let message):
            pendingAck = acknowledgement(requestID: requestID, state: "error", message: message)
            statusMessage = "Movie playback failed: \(message)"
        case .notOwned: pendingAck = acknowledgement(requestID: requestID, state: "error", message: "Movie playback was cancelled on the Mac.")
        }
    }

    private func invalidateSession(movies: MoviesModel, companion: CompanionModel, streamServer: MovieHTTPServer) {
        let hadSession = isReady || connectionSession.identity != nil
        _ = connectionSession.clear()
        isReady = false
        guard hadSession else { return }
        lifecycleGeneration &+= 1
        if let requestID = activeRequestID {
            movies.cancelBridgePreparation(requestID: requestID, companion: companion, streamServer: streamServer)
        }
        activeRequestID = nil
        pendingAck = nil
        uploadedPosterIDs.removeAll()
        lastPublishedFingerprint = nil
        syncState.markDirty()
    }

    private func connectionIdentity(_ connection: MovieTVConnection) -> String {
        "\(connection.host)\u{1F}\(connection.certificatePin)\u{1F}\(connection.client.token ?? "")"
    }

    private func currentContext() -> Context? {
        guard let movies, let companion, companion.apps.moviesEnabled, companion.isConnected,
              let connection = companion.movieTVConnection() else { return nil }
        return Context(dirtyGeneration: syncState.generation, lifecycleGeneration: lifecycleGeneration,
                       libraryID: sha256Hex(movies.state.folderBookmark ?? Data()), connectionID: connectionIdentity(connection))
    }

    private func isCurrent(_ context: Context) -> Bool {
        guard let current = currentContext() else { return false }
        return current.dirtyGeneration == context.dirtyGeneration
            && current.lifecycleGeneration == context.lifecycleGeneration
            && current.libraryID == context.libraryID
            && current.connectionID == context.connectionID
    }

    private func acknowledgement(requestID: String, state: String, message: String?) -> MovieLibraryAck {
        MovieLibraryAck(requestId: requestID, state: state, message: message.map { String($0.prefix(240)) })
    }

    private func isValidRequest(_ request: MovieLibraryRequest) -> Bool { Self.isValidRequest(request) }

    nonisolated static func isValidRequest(_ request: MovieLibraryRequest) -> Bool {
        UUID(uuidString: request.requestId) != nil && isSHA256(request.movieId)
            && ["queued", "preparing", "cancelled", "started", "error"].contains(request.state)
    }

    nonisolated static func isSHA256(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { byte in
            (48...57).contains(byte) || (97...102).contains(byte)
        }
    }
}

private enum MovieLibraryPoster {
    struct Input {
        let movieID: String
        let sourceURL: URL
    }

    struct Output {
        let movieID: String
        let id: String
        let data: Data
    }

    static func thumbnails(for inputs: [Input]) async -> [Output] {
        await Task.detached(priority: .utility) {
            inputs.compactMap { input in
                guard let poster = try? thumbnail(from: input.sourceURL) else { return nil }
                return Output(movieID: input.movieID, id: poster.id, data: poster.data)
            }
        }.value
    }

    private static func thumbnail(from sourceURL: URL) throws -> (id: String, data: Data) {
        guard let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil) else { throw Error.unreadable }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 384,
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { throw Error.unreadable }
        let scale = min(1, 256 / CGFloat(thumbnail.width), 384 / CGFloat(thumbnail.height))
        let width = max(1, Int((CGFloat(thumbnail.width) * scale).rounded()))
        let height = max(1, Int((CGFloat(thumbnail.height) * scale).rounded()))
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { throw Error.unreadable }
        context.interpolationQuality = .high
        context.draw(thumbnail, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let image = context.makeImage() else { throw Error.unreadable }
        for quality in [0.82, 0.68, 0.52] {
            let data = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { continue }
            CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
            guard CGImageDestinationFinalize(destination), data.count <= 256 * 1024 else { continue }
            let output = data as Data
            return (sha256Hex(output), output)
        }
        throw Error.tooLarge
    }

    enum Error: LocalizedError {
        case unreadable, tooLarge
        var errorDescription: String? { self == .unreadable ? "Movie artwork could not be read." : "Movie artwork is too large to share." }
    }
}

enum MovieLibraryBridgeChecks {
    static func run() throws {
        let legacyApps = try JSONDecoder().decode(AppsSettings.self, from: Data("{}".utf8))
        let invalidApps = try? JSONDecoder().decode(AppsSettings.self, from: Data(#"{"moviesEnabled":"true"}"#.utf8))
        let movieID = sha256Hex(Data("fixture movie".utf8))
        let payload = MovieLibraryPayload(apiVersion: 1, publisherId: "00000000-0000-4000-8000-000000000000",
                                          libraryId: sha256Hex(Data("fixture bookmark".utf8)),
                                          items: [MovieLibraryItemPayload(id: movieID, title: "Fixture", year: 2026, durationMs: 60_000,
                                                                          favorite: true, watched: false, playable: true, posterId: nil)])
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let encoded = try encoder.encode(payload)
        let decoded = try JSONDecoder().decode(MovieLibraryPayload.self, from: encoded)
        let valid = MovieLibraryRequest(requestId: "00000000-0000-4000-8000-000000000000", movieId: movieID, state: "queued")
        let invalid = MovieLibraryRequest(requestId: "not-a-uuid", movieId: String(repeating: "٠", count: 64), state: "queued")
        var sync = MovieLibrarySyncState()
        let stale = sync.generation
        sync.markDirty()
        let current = sync.generation
        let stalePublishRejected = !sync.markPublished(stale) && sync.shouldSynchronize && !sync.mayPoll
        let oversizeBlocked = sync.block(current) && !sync.shouldSynchronize && !sync.mayPoll
        sync.markDirty()
        let editedGeneration = sync.generation
        let editedGenerationPublishes = sync.shouldSynchronize && sync.markPublished(editedGeneration) && sync.mayPoll
        var connection = MovieLibraryConnectionSession()
        let connectionTransitions = connection.update("first") && !connection.update("first") && connection.update("second")
            && connection.clear() && connection.identity == nil
        guard !legacyApps.moviesEnabled,
              invalidApps == nil,
              decoded == payload,
              encoded.count <= 512 * 1024,
              !String(decoding: encoded, as: UTF8.self).contains("relativePath"),
              MovieLibraryBridge.isValidRequest(valid),
              !MovieLibraryBridge.isValidRequest(invalid),
              stalePublishRejected, oversizeBlocked, editedGenerationPublishes, connectionTransitions else {
            throw ProtocolSelfTest.SelfTestError.failed
        }
    }
}
