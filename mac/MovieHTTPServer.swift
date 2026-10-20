import Foundation
import Network
import Security
import Darwin

struct MovieStreamDescriptor: Codable {
    let url: URL
    let token: String
    let certificatePin: String
    let sessionId: String
    let movieId: String
    let title: String
}

final class MovieHTTPServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "local.glasstv.movies.stream")
    private let queueKey = DispatchSpecificKey<Bool>()
    private var listener: NWListener?
    private var session: Session?
    private var activeConnections: [ObjectIdentifier: NWConnection] = [:]
    private var fileHandles: [ObjectIdentifier: FileHandle] = [:]
    private var deadlines: [ObjectIdentifier: DispatchWorkItem] = [:]
    private var firstRequestAt: Date?
    private var lastActivityAt: Date?

    private struct Session {
        let fileURL: URL
        /// Held until the server stops so a user-triggered cache clear cannot unlink a movie
        /// that the receiver is actively reading.
        let cacheUse: ConversionCacheUseToken?
        let allowedPeerIPv4: String
        let opaqueID: String
        let token: String
        let certificatePin: String
        let identity: sec_identity_t
        let sessionID: String
        let generation: UUID
        let createdAt: Date
        let fileSize: Int64
        let modifiedAt: Date?
    }

    init() { queue.setSpecific(key: queueKey, value: true) }
    deinit { stop() }

    var isAvailable: Bool { synchronized { session.map { !expired($0) } ?? false } }

    /// Starts a one-file, one-TV TLS stream. Calling this revokes any preceding session.
    func start(movieURL: URL, cacheUse suppliedCacheUse: ConversionCacheUseToken? = nil, allowedPeerIPv4: String, sessionID: String, movieID: String = "", title: String = "") async throws -> MovieStreamDescriptor {
        let cacheUse: ConversionCacheUseToken?
        if let suppliedCacheUse {
            cacheUse = suppliedCacheUse
        } else {
            cacheUse = await ConversionCache.shared.acquireUse(for: movieURL)
        }
        var cacheUseOwnedBySession = false
        defer {
            if !cacheUseOwnedBySession, let cacheUse {
                Task { await ConversionCache.shared.releaseUse(cacheUse) }
            }
        }
        guard isIPv4(allowedPeerIPv4), let localIPv4 = IPv4Route.localAddress(reaching: allowedPeerIPv4) else { throw MovieLibraryError.noReachableIPv4 }
        let attributes = try FileManager.default.attributesOfItem(atPath: movieURL.path)
        guard let size = (attributes[.size] as? NSNumber)?.int64Value, size > 0 else { throw MovieHTTPServerError.unavailable }
        let identity = try MovieTLSIdentity.make(forIPv4: localIPv4)
        let pin = sha256Hex(SecCertificateCopyData(identity.certificate) as Data)
        let opaqueID = try randomURLToken(bytes: 18)
        let token = try randomURLToken(bytes: 32)
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, identity.secIdentity)
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(localIPv4), port: .any)
        let listener = try NWListener(using: parameters, on: .any)
        let active = Session(fileURL: movieURL, cacheUse: cacheUse, allowedPeerIPv4: allowedPeerIPv4, opaqueID: opaqueID, token: token,
                             certificatePin: pin, identity: identity.secIdentity, sessionID: sessionID, generation: UUID(), createdAt: Date(),
                             fileSize: size, modifiedAt: attributes[.modificationDate] as? Date)
        let port: NWEndpoint.Port = try await withCheckedThrowingContinuation { continuation in
            queue.async { [self, listener] in
            self.stopOnQueue()
            self.session = active
            cacheUseOwnedBySession = true
            self.listener = listener
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection, session: active) }
            let gate = ListenerContinuationGate()
            listener.stateUpdateHandler = { [weak listener] state in
                switch state {
                case .ready:
                    guard gate.claim() else { return }
                    if let port = listener?.port { continuation.resume(returning: port) }
                    else { continuation.resume(throwing: MovieHTTPServerError.unavailable) }
                case .failed(let error):
                    guard gate.claim() else { return }
                    continuation.resume(throwing: error)
                case .cancelled:
                    guard gate.claim() else { return }
                    continuation.resume(throwing: CancellationError())
                default: break
                }
            }
            listener.start(queue: self.queue)
            self.queue.asyncAfter(deadline: .now() + 5) {
                guard gate.claim() else { return }
                listener.cancel(); continuation.resume(throwing: MovieHTTPServerError.unavailable)
            }
            }
        }
        guard synchronized({ isCurrent(active) }), !Task.isCancelled else {
            synchronized { if isCurrent(active) { stopOnQueue() } }
            throw CancellationError()
        }
        guard let url = URL(string: "https://\(localIPv4):\(port.rawValue)/stream/\(opaqueID)") else { stop(); throw MovieHTTPServerError.unavailable }
        return MovieStreamDescriptor(url: url, token: token, certificatePin: pin, sessionId: sessionID, movieId: movieID, title: title)
    }

    func stop() {
        synchronized { stopOnQueue() }
    }

    private func synchronized<T>(_ work: () -> T) -> T {
        DispatchQueue.getSpecific(key: queueKey) == true ? work() : queue.sync(execute: work)
    }

    private func stopOnQueue() {
        let cacheUse = session?.cacheUse
        let closing = Array(activeConnections.values)
        activeConnections.removeAll()
        deadlines.values.forEach { $0.cancel() }; deadlines.removeAll()
        fileHandles.values.forEach { try? $0.close() }; fileHandles.removeAll()
        closing.forEach { $0.cancel() }
        listener?.cancel(); listener = nil; session = nil
        firstRequestAt = nil; lastActivityAt = nil
        if let cacheUse { Task { await ConversionCache.shared.releaseUse(cacheUse) } }
    }

    private func accept(_ connection: NWConnection, session: Session) {
        guard isCurrent(session), peerIPv4(connection) == session.allowedPeerIPv4, activeConnections.count < 4 else { connection.cancel(); return }
        activeConnections[ObjectIdentifier(connection)] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            if case .cancelled = state, let connection { self?.release(connection) }
            if case .failed = state, let connection { self?.release(connection) }
        }
        connection.start(queue: queue)
        let timeout = armDeadline(connection)
        receiveRequest(connection, session: session, buffer: Data(), timeout: timeout)
    }

    private func receiveRequest(_ connection: NWConnection, session: Session, buffer: Data, timeout: DispatchWorkItem) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, complete, error in
            guard let self, self.isCurrent(session), error == nil, let data else { self?.release(connection); return }
            var combined = buffer; combined.append(data)
            guard combined.count <= 16 * 1024 else { self.respond(connection, status: 431, headers: [:], body: Data()); return }
            guard let range = combined.range(of: Data("\r\n\r\n".utf8)) else {
                if complete { self.release(connection) } else { self.receiveRequest(connection, session: session, buffer: combined, timeout: timeout) }
                return
            }
            timeout.cancel()
            self.handleRequest(String(decoding: combined[..<range.lowerBound], as: UTF8.self), connection: connection, session: session)
        }
    }

    private func handleRequest(_ raw: String, connection: NWConnection, session: Session) {
        guard isCurrent(session), !expired(session), fileStillMatches(session) else { respond(connection, status: 410, headers: ["Cache-Control": "no-store"], body: Data()); return }
        let lines = raw.components(separatedBy: "\r\n")
        guard let request = lines.first?.split(separator: " "), request.count == 3,
              let method = request.first.map(String.init), ["GET", "HEAD"].contains(method),
              request[2] == "HTTP/1.1" || request[2] == "HTTP/1.0" else { respond(connection, status: 400, headers: [:], body: Data()); return }
        let path = String(request[1])
        guard path == "/stream/\(session.opaqueID)", !path.contains("?") else { respond(connection, status: 404, headers: [:], body: Data()); return }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let index = line.firstIndex(of: ":") else { respond(connection, status: 400, headers: [:], body: Data()); return }
            let key = line[..<index].lowercased()
            guard headers[key] == nil else { respond(connection, status: 400, headers: [:], body: Data()); return }
            headers[key] = line[line.index(after: index)...].trimmingCharacters(in: .whitespaces)
        }
        guard let authorization = headers["authorization"], constantTimeEqual(authorization, "Bearer \(session.token)") else { respond(connection, status: 401, headers: ["WWW-Authenticate": "Bearer"], body: Data()); return }
        firstRequestAt = firstRequestAt ?? Date(); lastActivityAt = Date()
        let size = session.fileSize
        let etag = "\"\(session.opaqueID)-\(session.fileSize)\""
        let byteRange: ClosedRange<Int64>?
        do { byteRange = try parseRange(headers["if-range"].map { $0 == etag } == false ? nil : headers["range"], length: size) }
        catch { respond(connection, status: 416, headers: ["Content-Range": "bytes */\(size)"], body: Data()); return }
        let selected = byteRange ?? 0...(size - 1)
        let partial = byteRange != nil
        var responseHeaders = [
            "Accept-Ranges": "bytes",
            "Cache-Control": "no-store",
            "ETag": etag,
            "Content-Type": mimeType(for: session.fileURL),
            "Content-Length": "\(selected.count)"
        ]
        if partial { responseHeaders["Content-Range"] = "bytes \(selected.lowerBound)-\(selected.upperBound)/\(size)" }
        sendHeaders(connection, status: partial ? 206 : 200, headers: responseHeaders) { [weak self] error in
            guard error == nil else { self?.release(connection); return }
            guard method == "GET" else { self?.finish(connection); return }
            self?.sendFile(connection, session: session, range: selected)
        }
    }

    private func sendFile(_ connection: NWConnection, session: Session, range: ClosedRange<Int64>) {
        guard isCurrent(session), fileStillMatches(session), let handle = try? FileHandle(forReadingFrom: session.fileURL) else { release(connection); return }
        do { try handle.seek(toOffset: UInt64(range.lowerBound)) } catch { try? handle.close(); release(connection); return }
        fileHandles[ObjectIdentifier(connection)] = handle
        sendChunk(connection, handle: handle, remaining: Int64(range.count), session: session)
    }

    private func sendChunk(_ connection: NWConnection, handle: FileHandle, remaining: Int64, session: Session) {
        guard isCurrent(session), !expired(session), fileStillMatches(session) else { release(connection); return }
        guard remaining > 0 else { finish(connection); return }
        let count = Int(min(64 * 1024, remaining))
        guard let data = try? handle.read(upToCount: count), !data.isEmpty else { try? handle.close(); release(connection); return }
        lastActivityAt = Date()
        _ = armDeadline(connection)
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            guard error == nil else { try? handle.close(); self?.release(connection); return }
            self?.queue.async { self?.sendChunk(connection, handle: handle, remaining: remaining - Int64(data.count), session: session) }
        })
    }

    private func respond(_ connection: NWConnection, status: Int, headers: [String: String], body: Data) {
        var values = headers; values["Content-Length"] = values["Content-Length"] ?? "\(body.count)"
        sendHeaders(connection, status: status, headers: values) { error in
            guard error == nil else { self.release(connection); return }
            guard !body.isEmpty else { self.finish(connection); return }
            connection.send(content: body, completion: .contentProcessed { error in if error == nil { self.finish(connection) } else { self.release(connection) } })
        }
    }

    private func sendHeaders(_ connection: NWConnection, status: Int, headers: [String: String], completion: @escaping (NWError?) -> Void) {
        _ = armDeadline(connection)
        let reason: String = [410: "Gone", 200: "OK", 206: "Partial Content", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found", 416: "Range Not Satisfiable", 431: "Request Header Fields Too Large"][status] ?? "Error"
        var text = "HTTP/1.1 \(status) \(reason)\r\nConnection: close\r\n"
        for (name, value) in headers { text += "\(name): \(value)\r\n" }
        text += "\r\n"
        connection.send(content: Data(text.utf8), completion: .contentProcessed(completion))
    }

    @discardableResult
    private func armDeadline(_ connection: NWConnection) -> DispatchWorkItem {
        let id = ObjectIdentifier(connection)
        deadlines.removeValue(forKey: id)?.cancel()
        let task = DispatchWorkItem { [weak self, weak connection] in
            guard let connection else { return }; self?.release(connection)
        }
        deadlines[id] = task
        queue.asyncAfter(deadline: .now() + 30, execute: task)
        return task
    }
    private func finish(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        if let handle = fileHandles.removeValue(forKey: id) { try? handle.close() }
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] error in
            guard error == nil else { self?.release(connection); return }
            // Keep TLS/TCP alive until the peer has consumed the response and closes its side.
            // Cancelling immediately after enqueueing the final bytes can truncate a large range.
            connection.receive(minimumIncompleteLength: 1, maximumLength: 1) { [weak self] _, _, _, _ in self?.release(connection) }
        })
    }
    private func release(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        deadlines.removeValue(forKey: id)?.cancel()
        if let handle = fileHandles.removeValue(forKey: id) { try? handle.close() }
        activeConnections.removeValue(forKey: id)
        connection.stateUpdateHandler = nil
        connection.cancel()
    }
    private func isCurrent(_ candidate: Session) -> Bool { session?.generation == candidate.generation }
    private func expired(_ candidate: Session) -> Bool {
        let now = Date()
        if now.timeIntervalSince(candidate.createdAt) > 8 * 60 * 60 { return true }
        if firstRequestAt == nil { return now.timeIntervalSince(candidate.createdAt) > 60 }
        return now.timeIntervalSince(lastActivityAt ?? candidate.createdAt) > 10 * 60
    }
    private func fileStillMatches(_ candidate: Session) -> Bool {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: candidate.fileURL.path),
              (attributes[.size] as? NSNumber)?.int64Value == candidate.fileSize else { return false }
        return (attributes[.modificationDate] as? Date) == candidate.modifiedAt
    }
}

enum MovieHTTPServerError: LocalizedError { case unavailable; var errorDescription: String? { "Could not start the local movie stream." } }

private final class ListenerContinuationGate: @unchecked Sendable {
    private let lock = NSLock(); private var finished = false
    func claim() -> Bool { lock.lock(); defer { lock.unlock() }; guard !finished else { return false }; finished = true; return true }
}

private func parseRange(_ value: String?, length: Int64) throws -> ClosedRange<Int64>? {
    guard let value else { return nil }
    guard value.hasPrefix("bytes="), !value.contains(",") else { throw MovieHTTPServerError.unavailable }
    let spec = String(value.dropFirst(6))
    let parts = spec.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
    guard parts.count == 2 else { throw MovieHTTPServerError.unavailable }
    if parts[0].isEmpty {
        guard let suffix = Int64(parts[1]), suffix > 0 else { throw MovieHTTPServerError.unavailable }
        return max(0, length - suffix)...(length - 1)
    }
    guard let start = Int64(parts[0]), start >= 0, start < length else { throw MovieHTTPServerError.unavailable }
    let end = parts[1].isEmpty ? length - 1 : min(Int64(parts[1]) ?? -1, length - 1)
    guard end >= start else { throw MovieHTTPServerError.unavailable }
    return start...end
}

private func randomURLToken(bytes: Int) throws -> String {
    var data = Data(count: bytes)
    guard data.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, bytes, $0.baseAddress!) }) == errSecSuccess else { throw MovieHTTPServerError.unavailable }
    return data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
}

private func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
    let a = Array(lhs.utf8), b = Array(rhs.utf8); guard a.count == b.count else { return false }
    return zip(a, b).reduce(0) { $0 | Int($1.0 ^ $1.1) } == 0
}

private func isIPv4(_ value: String) -> Bool { var address = in_addr(); return inet_pton(AF_INET, value, &address) == 1 }
private func mimeType(for url: URL) -> String { switch url.pathExtension.lowercased() { case "mp4", "m4v": return "video/mp4"; case "mov": return "video/quicktime"; case "mkv": return "video/x-matroska"; default: return "application/octet-stream" } }

private func peerIPv4(_ connection: NWConnection) -> String? {
    guard case let .hostPort(host, _) = connection.endpoint else { return nil }
    let string = "\(host)"; return isIPv4(string) ? string : nil
}

private enum IPv4Route {
    static func localAddress(reaching host: String) -> String? {
        if host == "127.0.0.1" { return "127.0.0.1" }
        var target = sockaddr_in(); target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); target.sin_family = sa_family_t(AF_INET); target.sin_port = UInt16(9).bigEndian
        guard inet_pton(AF_INET, host, &target.sin_addr) == 1 else { return nil }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP); guard fd >= 0 else { return nil }; defer { close(fd) }
        let connected = withUnsafePointer(to: &target) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        guard connected == 0 else { return nil }
        var local = sockaddr_in(); var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        guard withUnsafeMutablePointer(to: &local, { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) } }) == 0 else { return nil }
        var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &local.sin_addr, &text, socklen_t(text.count)) != nil else { return nil }
        return String(cString: text)
    }
}

