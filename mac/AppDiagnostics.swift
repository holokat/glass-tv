import AppKit
import Combine
import UniformTypeIdentifiers

/// Records only fixed event labels and numeric error codes. No pairing data,
/// addresses, media paths or server-provided error text enters this log.
@MainActor
final class AppDiagnostics: ObservableObject {
    static let shared = AppDiagnostics()

    struct Entry: Codable, Identifiable {
        let id: UUID
        let date: Date
        let message: String
    }

    enum Event: String {
        case started = "App opened"
        case connecting = "Connecting"
        case connected = "TV connected"
        case disconnected = "Disconnected"
        case switched = "TV changed"
        case discovering = "Checking TV certificate"
        case paired = "TV paired"
        case discoveryFailed = "Certificate check failed"
        case pairingFailed = "Pairing failed"
        case statusFailed = "Connection check failed"
        case syncFailed = "Sync failed"
    }

    @Published private(set) var entries: [Entry]
    private let file: URL
    private static let capacity = 200

    private init() {
        file = AppPaths.applicationSupport.appendingPathComponent("connection-log.json")
        let saved = (try? Data(contentsOf: file))
            .flatMap { try? JSONDecoder().decode([Entry].self, from: $0) } ?? []
        entries = Array(saved.filter { Self.isAllowed($0.message) }.suffix(Self.capacity))
        record(.started)
    }

    func record(_ event: Event, errorCode: Int? = nil) {
        let message = event.rawValue + (errorCode.map { " (\($0))" } ?? "")
        guard entries.last?.message != message else { return }
        entries.append(Entry(id: UUID(), date: Date(), message: message))
        entries = Array(entries.suffix(Self.capacity))
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: file, options: .atomic)
        }
    }

    var logText: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .medium
        return entries.map { "\(formatter.string(from: $0.date))  \($0.message)" }.joined(separator: "\n")
    }

    func export() throws {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.plainText]
        panel.nameFieldStringValue = "Glass TV diagnostics.txt"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unknown"
        let report = "Glass TV \(version) (\(build))\n\(ProcessInfo.processInfo.operatingSystemVersionString)\n\n\(logText)\n"
        try report.write(to: url, atomically: true, encoding: .utf8)
    }

    nonisolated private static func isAllowed(_ message: String) -> Bool {
        let labels = [
            Event.started, .connecting, .connected, .disconnected, .switched,
            .discovering, .paired, .discoveryFailed, .pairingFailed, .statusFailed, .syncFailed
        ]
        return labels.contains { event in
            message == event.rawValue || message.range(
                of: "^" + NSRegularExpression.escapedPattern(for: event.rawValue) + #" \(-?[0-9]+\)$"#,
                options: .regularExpression
            ) != nil
        }
    }

    nonisolated static func selfTestsPass() -> Bool {
        isAllowed("TV connected")
            && isAllowed("Connection check failed (-1009)")
            && !isAllowed("Connection check failed (secret)")
            && !isAllowed("TV connected https://private.example")
            && !isAllowed("sensitive-local-file.mov")
    }
}
