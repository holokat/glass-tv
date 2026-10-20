import Foundation
import AppKit

enum VerificationRunner {
    private static var configPath: String?
    private static var importCredential = false

    static func configure(arguments: [String]) {
        if let index = arguments.firstIndex(of: "--verify-connection"), arguments.indices.contains(index + 1) {
            configPath = arguments[index + 1]
            importCredential = arguments.contains("--import-pairing")
        }
    }

    static func scheduleIfNeeded() {
        guard let configPath else { return }
        Task { @MainActor in
            do {
                let url = URL(fileURLWithPath: configPath)
                let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                guard let permissions = attributes[.posixPermissions] as? NSNumber, (permissions.intValue & 0o077) == 0 else {
                    throw VerificationError.insecureFile
                }
                let config = try JSONDecoder().decode(QASession.self, from: Data(contentsOf: url)).credential
                _ = try await GlassTVClient(host: config.host, pin: config.certificatePin, token: config.token).status()
                if importCredential { try KeychainStore.save(config) }
                print(importCredential ? "Glass TV connection verified and imported." : "Glass TV connection verified.")
            } catch {
                fputs("Glass TV connection verification failed: \(safeDiagnostic(error))\n", stderr)
                exit(EXIT_FAILURE)
            }
            exit(EXIT_SUCCESS)
        }
    }

    enum VerificationError: LocalizedError { case insecureFile; var errorDescription: String? { "Pairing configuration must be owner-readable only (chmod 600)." } }
}

private func safeDiagnostic(_ error: Error) -> String {
    var parts: [String] = []
    var current: NSError? = error as NSError
    while let value = current, parts.count < 3 {
        parts.append("\(value.domain) (\(value.code)): \(value.localizedDescription)")
        current = value.userInfo[NSUnderlyingErrorKey] as? NSError
    }
    return parts.joined(separator: " → ")
}

private struct QASession: Decodable {
    let address: String?
    let host: String?
    let fingerprint: String
    let token: String
    let deviceId: String

    var credential: PairCredential {
        PairCredential(host: host ?? address ?? "", deviceId: deviceId, token: token, certificatePin: fingerprint.lowercased())
    }
}
