import Foundation
import Security

enum GlassTVClientError: LocalizedError {
    case invalidAddress
    case certificateUnavailable
    case certificateMismatch
    case unexpectedResponse(Int, String)
    case malformedResponse

    var errorDescription: String? {
        switch self {
        case .invalidAddress: return "Enter a valid IPv4 address for the TV."
        case .certificateUnavailable: return "The TV did not present a certificate. Keep the launcher visible and try again."
        case .certificateMismatch: return "The TV certificate does not match the confirmed fingerprint. Pair again only after checking the TV."
        case .unexpectedResponse(let code, let message): return message.isEmpty ? "TV returned HTTP \(code)." : message
        case .malformedResponse: return "The TV returned an unexpected response."
        }
    }
}

private final class TrustDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate {
    let expectedPin: String?
    private let lock = NSLock()
    private var capturedPin: String?

    init(expectedPin: String?) { self.expectedPin = expectedPin }

    var leafPin: String? { lock.lock(); defer { lock.unlock() }; return capturedPin }

    private func handle(_ challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let certificate = SecTrustGetCertificateAtIndex(trust, 0) else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        let pin = sha256Hex(SecCertificateCopyData(certificate) as Data)
        lock.lock(); capturedPin = pin; lock.unlock()
        if let expectedPin, expectedPin != pin {
            completionHandler(.cancelAuthenticationChallenge, nil)
        } else {
            // Pairing discovery intentionally accepts the self-signed TV certificate once.
            completionHandler(.useCredential, URLCredential(trust: trust))
        }
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        handle(challenge, completionHandler: completionHandler)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        handle(challenge, completionHandler: completionHandler)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

struct GlassTVClient {
    let host: String
    let pin: String?
    let token: String?

    private func endpoint(_ path: String) throws -> URL {
        guard host.split(separator: ".").count == 4,
              host.split(separator: ".").allSatisfy({ Int($0).map { (0...255).contains($0) } ?? false }),
              let url = URL(string: "https://\(host):8743\(path)") else { throw GlassTVClientError.invalidAddress }
        return url
    }

    private func session() -> (URLSession, TrustDelegate) {
        let delegate = TrustDelegate(expectedPin: pin)
        let configuration = secureConfiguration()
        return (URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil), delegate)
    }

    private func secureConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 1_800
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        // The launcher deliberately serves TLS 1.2 locally; keeping this explicit avoids an unreliable TLS 1.3 negotiation path.
        configuration.tlsMinimumSupportedProtocolVersion = .TLSv12
        configuration.tlsMaximumSupportedProtocolVersion = .TLSv12
        return configuration
    }

    private func errorMessage(_ data: Data) -> String {
        (try? JSONDecoder().decode(APIErrorEnvelope.self, from: data).error.message) ?? ""
    }

    private func request(path: String, method: String = "GET", body: Data? = nil, contentType: String? = nil, authenticated: Bool = true, timeout: TimeInterval? = nil) async throws -> Data {
        let url = try endpoint(path)
        var request = URLRequest(url: url)
        if let timeout { request.timeoutInterval = timeout }
        request.httpMethod = method
        request.httpBody = body
        request.setValue("close", forHTTPHeaderField: "Connection")
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let body { request.setValue(String(body.count), forHTTPHeaderField: "Content-Length") }
        if authenticated, let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (session, delegate) = session()
        defer { session.invalidateAndCancel() }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw GlassTVClientError.malformedResponse }
            if let pin, let observedPin = delegate.leafPin, observedPin != pin { throw GlassTVClientError.certificateMismatch }
            guard (200...299).contains(http.statusCode) else { throw GlassTVClientError.unexpectedResponse(http.statusCode, errorMessage(data)) }
            return data
        } catch let error as GlassTVClientError { throw error }
        catch {
            if let pin, let observedPin = delegate.leafPin, observedPin != pin { throw GlassTVClientError.certificateMismatch }
            throw error
        }
    }

    func discoverCertificate() async throws -> String {
        do { _ = try await request(path: "/status", authenticated: false) }
        catch {
            // A 401 is expected before pairing. The handshake still supplied the fingerprint.
            if let cert = await certificateFromHandshake() { return cert }
            throw error
        }
        guard let cert = await certificateFromHandshake() else { throw GlassTVClientError.certificateUnavailable }
        return cert
    }

    private func certificateFromHandshake() async -> String? {
        // request uses a short-lived delegate; repeat a harmless unauthenticated request only if needed.
        guard let url = try? endpoint("/status") else { return nil }
        let delegate = TrustDelegate(expectedPin: pin)
        let session = URLSession(configuration: secureConfiguration(), delegate: delegate, delegateQueue: nil)
        var request = URLRequest(url: url); request.timeoutInterval = 12; request.setValue("close", forHTTPHeaderField: "Connection")
        _ = try? await session.data(for: request)
        session.invalidateAndCancel()
        return delegate.leafPin
    }

    func pair(code: String) async throws -> PairReply {
        let payload = ["apiVersion": 1, "code": code, "clientName": Host.current().localizedName ?? "Mac"] as [String : Any]
        let data = try JSONSerialization.data(withJSONObject: payload)
        let reply = try await request(path: "/pair", method: "POST", body: data, contentType: "application/json", authenticated: false)
        guard let decoded = try? JSONDecoder().decode(PairReply.self, from: reply), decoded.apiVersion == 1 else { throw GlassTVClientError.malformedResponse }
        return decoded
    }

    func status(timeout: TimeInterval? = nil) async throws -> ServerStatus {
        let data = try await request(path: "/status", timeout: timeout)
        guard let decoded = try? JSONDecoder().decode(ServerStatus.self, from: data), decoded.apiVersion == 1 else { throw GlassTVClientError.malformedResponse }
        return decoded
    }

    func upload(jpeg: Data, digest: String) async throws {
        _ = try await request(path: "/images/\(digest).jpg", method: "PUT", body: jpeg, contentType: "image/jpeg")
    }

    func uploadVideo(file: URL, digest: String) async throws {
        let url = try endpoint("/videos/\(digest).mp4")
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        let length = (attributes[.size] as? NSNumber)?.intValue ?? 0
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("close", forHTTPHeaderField: "Connection")
        request.setValue("video/mp4", forHTTPHeaderField: "Content-Type")
        request.setValue(String(length), forHTTPHeaderField: "Content-Length")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (session, delegate) = session()
        defer { session.invalidateAndCancel() }
        do {
            let (data, response) = try await session.upload(for: request, fromFile: file)
            guard let http = response as? HTTPURLResponse else { throw GlassTVClientError.malformedResponse }
            if let pin, let observedPin = delegate.leafPin, observedPin != pin { throw GlassTVClientError.certificateMismatch }
            guard (200...299).contains(http.statusCode) else { throw GlassTVClientError.unexpectedResponse(http.statusCode, errorMessage(data)) }
        } catch let error as GlassTVClientError { throw error }
        catch {
            if let pin, let observedPin = delegate.leafPin, observedPin != pin { throw GlassTVClientError.certificateMismatch }
            throw error
        }
    }

    func putPlaylist(baseRevision: Int, intervalSeconds: Int, shuffle: Bool, images: [PlaylistImage], selectedInputId: String?, videoPlayback: VideoPlayback, loopVideoId: String?, muteVideos: Bool, slideshowEnabled: Bool = true, fitMode: FitMode = .fill, showTVInterface: Bool = true, defaultBackgroundEnabled: Bool? = nil, weather: WeatherSettings? = nil, apps: AppsSettings? = nil, layout: LayoutSettings? = nil) async throws -> Int {
        var value: [String: Any] = ["apiVersion": 1, "baseRevision": baseRevision, "intervalSeconds": intervalSeconds, "shuffle": shuffle,
                                    "images": images.map { ["id": $0.id, "name": $0.name, "kind": ($0.kind ?? .photo).rawValue] },
                                    "videoPlayback": videoPlayback.rawValue, "muteVideos": muteVideos,
                                    "slideshowEnabled": slideshowEnabled, "fitMode": fitMode.rawValue,
                                    "showTVInterface": showTVInterface]
        if let selectedInputId { value["selectedInputId"] = selectedInputId }
        if let loopVideoId { value["loopVideoId"] = loopVideoId }
        if let defaultBackgroundEnabled { value["defaultBackgroundEnabled"] = defaultBackgroundEnabled }
        if let weather {
            value["weather"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(weather))
        }
        if let apps {
            value["apps"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(apps))
        }
        if let layout {
            value["layout"] = layout.wireObject()
        }
        let body = try JSONSerialization.data(withJSONObject: value)
        let data = try await request(path: "/playlist", method: "PUT", body: body, contentType: "application/json")
        guard let response = try? JSONDecoder().decode(RevisionReply.self, from: data) else { throw GlassTVClientError.malformedResponse }
        return response.revision
    }

    func moviePlay(_ payload: MoviePlayPayload) async throws -> MovieStatusSnapshot {
        let data = try JSONEncoder().encode(payload)
        return try await movieResponse(path: "/movies/play", method: "POST", body: data)
    }

    func movieStatus() async throws -> MovieStatusSnapshot {
        try await movieResponse(path: "/movies/status", method: "GET", body: nil)
    }

    func movieControl(_ payload: MovieControlPayload) async throws -> MovieStatusSnapshot {
        let data = try JSONEncoder().encode(payload)
        return try await movieResponse(path: "/movies/control", method: "POST", body: data)
    }

    private func movieResponse(path: String, method: String, body: Data?) async throws -> MovieStatusSnapshot {
        // The TV may be releasing its ambient decoder when a movie starts. Reuse the
        // same session payload while it is busy, keeping the pinned stream alive.
        for attempt in 0..<4 {
            do {
                let data = try await request(path: path, method: method, body: body, contentType: body == nil ? nil : "application/json")
                guard let response = try? JSONDecoder().decode(MovieStatusSnapshot.self, from: data) else { throw GlassTVClientError.malformedResponse }
                return response
            } catch let error as GlassTVClientError {
                guard case .unexpectedResponse(503, _) = error, attempt < 3 else { throw error }
                try await Task.sleep(nanoseconds: 500_000_000)
            }
        }
        throw GlassTVClientError.malformedResponse
    }
}

extension GlassTVClient {
    func putMovieLibrary(_ payload: MovieLibraryPayload) async throws -> MovieLibraryReply {
        let body = try JSONEncoder().encode(payload)
        let data = try await request(path: "/movies/library", method: "PUT", body: body, contentType: "application/json")
        guard let reply = try? JSONDecoder().decode(MovieLibraryReply.self, from: data) else {
            throw GlassTVClientError.malformedResponse
        }
        return reply
    }

    func uploadMovieLibraryPoster(jpeg: Data, id: String) async throws -> MovieLibraryReply {
        let data = try await request(path: "/movies/library/posters/\(id).jpg", method: "PUT", body: jpeg, contentType: "image/jpeg")
        guard let reply = try? JSONDecoder().decode(MovieLibraryReply.self, from: data) else {
            throw GlassTVClientError.malformedResponse
        }
        return reply
    }

    func pollMovieLibrary(_ payload: MovieLibraryPollPayload) async throws -> MovieLibraryPollReply {
        let body = try JSONEncoder().encode(payload)
        let data = try await request(path: "/movies/library/poll", method: "POST", body: body, contentType: "application/json")
        guard let reply = try? JSONDecoder().decode(MovieLibraryPollReply.self, from: data) else {
            throw GlassTVClientError.malformedResponse
        }
        return reply
    }
}
