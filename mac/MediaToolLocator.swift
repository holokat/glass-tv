import Foundation

enum MediaToolLocator {
    static func executable(named name: String) -> URL? {
        var candidates: [URL] = []
        if let resourceURL = Bundle.main.resourceURL {
            candidates.append(resourceURL.appendingPathComponent("Tools/\(name)"))
        }
        candidates.append(contentsOf: [
            URL(fileURLWithPath: "/opt/homebrew/bin/\(name)"),
            URL(fileURLWithPath: "/usr/local/bin/\(name)")
        ])
        let pathEntries = ProcessInfo.processInfo.environment["PATH"]?.split(separator: ":") ?? []
        candidates.append(contentsOf: pathEntries.map { URL(fileURLWithPath: String($0)).appendingPathComponent(name) })

        var seen = Set<String>()
        return candidates.first { candidate in
            seen.insert(candidate.path).inserted && FileManager.default.isExecutableFile(atPath: candidate.path)
        }
    }
}
