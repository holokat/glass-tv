import Foundation
import Security

enum AppPaths {
    static let applicationSupport: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directory = base.appendingPathComponent("Glass TV", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }()
    static let photos: URL = {
        let directory = applicationSupport.appendingPathComponent("Photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }()
    static let settings = applicationSupport.appendingPathComponent("library.json")
    static let tvProfiles = applicationSupport.appendingPathComponent("tv-profiles.json")
    static let movies = applicationSupport.appendingPathComponent("movies.json")
    static let movieFrames: URL = {
        let directory = applicationSupport.appendingPathComponent("Movie Frames", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }()
}

final class LibraryStore {
    func load() -> LocalSettings {
        guard let data = try? Data(contentsOf: AppPaths.settings),
              let decoded = try? JSONDecoder().decode(LocalSettings.self, from: data) else { return LocalSettings() }
        return decoded
    }
    func save(_ settings: LocalSettings) throws {
        let data = try JSONEncoder().encode(settings)
        try data.write(to: AppPaths.settings, options: .atomic)
    }
    func loadTVProfiles() -> TVProfiles? {
        guard let data = try? Data(contentsOf: AppPaths.tvProfiles) else { return nil }
        return try? JSONDecoder().decode(TVProfiles.self, from: data)
    }
    /// Keep the active profile mirrored in the legacy file so existing local
    /// libraries remain readable by prior companion versions.
    func save(_ profiles: TVProfiles, activeSettings: LocalSettings) throws {
        // Write the compatibility snapshot first. If the profile document then
        // fails to write, its previous active receiver remains authoritative.
        try save(activeSettings)
        let data = try JSONEncoder().encode(profiles)
        try data.write(to: AppPaths.tvProfiles, options: .atomic)
    }
}

enum KeychainStore {
    private static let service = "local.glasstv.companion"

    static func save(_ credential: PairCredential) throws {
        let data = try JSONEncoder().encode(credential)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: credential.host]
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }

    static func load(host: String) -> PairCredential? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: host,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(PairCredential.self, from: data)
    }

    /// Enumerates only this app's host-scoped pairing records for migration.
    /// Credentials remain in memory and are never logged or exposed to the UI.
    static func allCredentials() -> [PairCredential] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { item in
            guard let data = item[kSecValueData as String] as? Data else { return nil }
            return try? JSONDecoder().decode(PairCredential.self, from: data)
        }
    }

    static func delete(host: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: host]
        SecItemDelete(query as CFDictionary)
    }

    enum KeychainError: LocalizedError { case unexpectedStatus(OSStatus); var errorDescription: String? { "Could not store pairing credentials in Keychain." } }
}
