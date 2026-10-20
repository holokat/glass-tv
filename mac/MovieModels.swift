import Foundation

struct MovieLibraryState: Codable {
    var folderBookmark: Data?
    var folderName: String?
    var movies: [MovieItem]
    var progress: [String: MovieProgress]
    var favoriteIDs: Set<String> = []
    var watchedIDs: Set<String> = []

    init(folderBookmark: Data? = nil, folderName: String? = nil, movies: [MovieItem] = [], progress: [String: MovieProgress] = [:]) {
        self.folderBookmark = folderBookmark
        self.folderName = folderName
        self.movies = movies
        self.progress = progress
    }
    private enum CodingKeys: String, CodingKey {
        case folderBookmark, folderName, movies, progress, favoriteIDs, watchedIDs
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        folderBookmark = try values.decodeIfPresent(Data.self, forKey: .folderBookmark)
        folderName = try values.decodeIfPresent(String.self, forKey: .folderName)
        movies = try values.decodeIfPresent([MovieItem].self, forKey: .movies) ?? []
        progress = try values.decodeIfPresent([String: MovieProgress].self, forKey: .progress) ?? [:]
        favoriteIDs = try values.decodeIfPresent(Set<String>.self, forKey: .favoriteIDs) ?? []
        watchedIDs = try values.decodeIfPresent(Set<String>.self, forKey: .watchedIDs) ?? []
    }

    func visibleMovies(in collection: MovieCollection, query: String = "") -> [MovieItem] {
        let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return movies.filter { movie in
            let included: Bool
            switch collection {
            case .all: included = !watchedIDs.contains(movie.id)
            case .favorites: included = favoriteIDs.contains(movie.id)
            case .watched: included = watchedIDs.contains(movie.id)
            }
            let fields = [movie.displayTitle, movie.displayYear.map(String.init) ?? ""]
                + (movie.metadata?.genres ?? []) + (movie.metadata?.cast ?? [])
            return included && (search.isEmpty || fields.contains { $0.localizedCaseInsensitiveContains(search) })
        }.sorted { lhs, rhs in
            let leftFavorite = favoriteIDs.contains(lhs.id), rightFavorite = favoriteIDs.contains(rhs.id)
            if leftFavorite != rightFavorite { return leftFavorite }
            let order = lhs.displayTitle.localizedCaseInsensitiveCompare(rhs.displayTitle)
            return order == .orderedSame ? lhs.id < rhs.id : order == .orderedAscending
        }
    }

}

enum MovieCollection: String, CaseIterable, Identifiable {
    case all = "All", favorites = "Favorites", watched = "Watched"
    var id: Self { self }
}

struct MovieItem: Codable, Hashable, Identifiable {
    let id: String
    let relativePath: String
    let fileName: String
    let title: String
    let year: Int?
    let durationMs: Int64?
    let width: Int?
    let height: Int?
    let fileSize: Int64
    let modifiedAt: Date?
    let posterRelativePath: String?
    let frameFileName: String?
    var cachedPosterFileName: String?
    let decodeError: String?
    var metadata: MovieMetadata?

    var displayTitle: String { metadata?.title.isEmpty == false ? metadata!.title : title }
    var displayYear: Int? { metadata?.year ?? year }
}

struct MovieProgress: Codable, Hashable {
    var positionMs: Int64
    var durationMs: Int64
    var updatedAt: Date
}

struct MovieStatusSnapshot: Codable, Hashable {
    enum State: String, Codable { case idle, preparing, playing, paused, completed, error }
    let supported: Bool
    let sessionId: String?
    let movieId: String?
    let title: String?
    let state: State?
    let positionMs: Int64?
    let durationMs: Int64?
    let width: Int?
    let height: Int?
    let error: String?
}

enum MovieControlAction: String, Codable { case pause, resume, seek, stop }

struct MoviePlayPayload: Codable {
    let apiVersion: Int
    let sessionId: String
    let movieId: String
    let title: String
    let url: String
    let token: String
    let certificatePin: String
    let resumePositionMs: Int64
}

struct MovieControlPayload: Codable {
    let apiVersion: Int
    let sessionId: String
    let action: MovieControlAction
    let positionMs: Int64?
}

struct MovieTVConnection {
    let host: String
    let certificatePin: String
    let client: GlassTVClient
}

enum MovieLibraryError: LocalizedError {
    case noFolder
    case bookmarkUnavailable
    case unsupportedFile(String)
    case noReachableIPv4

    var errorDescription: String? {
        switch self {
        case .noFolder: return "Choose a movie folder first."
        case .bookmarkUnavailable: return "The selected movie folder is no longer available. Choose it again."
        case .unsupportedFile(let name): return "\(name) is not a supported video file."
        case .noReachableIPv4: return "A local IPv4 address that can reach the TV is required for streaming."
        }
    }
}

enum MovieFileKind {
    // ffprobe makes the final decision. This deliberately covers common containers rather
    // than limiting the library to the receiver's native MP4 subset, because the shared
    // compatibility layer can prepare a playable H.264/AAC copy when needed.
    private static let videoExtensions: Set<String> = [
        "3g2", "3gp", "asf", "avi", "av1", "dv", "f4v", "flv", "m2t", "m2ts", "m4v", "mkv",
        "mov", "mp4", "mpeg", "mpg", "mts", "mxf", "ogv", "qt", "rm", "rmvb", "ts", "vob",
        "webm", "wmv"
    ]

    private static let nonVideoExtensions: Set<String> = [
        "aac", "aif", "aiff", "bmp", "css", "csv", "gif", "heic", "html", "jpeg", "jpg", "json",
        "md", "m4a", "m4b", "mp3", "nfo", "pdf", "png", "srt", "ssa", "svg", "txt", "vtt", "wav",
        "webp", "xml", "yaml", "yml"
    ]

    static func accepts(_ url: URL) -> Bool {
        videoExtensions.contains(url.pathExtension.lowercased())
    }

    static func shouldProbe(_ url: URL) -> Bool {
        !nonVideoExtensions.contains(url.pathExtension.lowercased())
    }
}

func movieStableID(relativePath: String) -> String {
    sha256Hex(Data(relativePath.precomposedStringWithCanonicalMapping.utf8))
}

func movieTitleAndYear(from filename: String) -> (title: String, year: Int?) {
    let name = URL(fileURLWithPath: filename).deletingPathExtension().lastPathComponent
    let expression = try? NSRegularExpression(pattern: #"\b(19\d{2}|20\d{2})\b"#)
    let matches = expression?.matches(in: name, range: NSRange(name.startIndex..., in: name)) ?? []
    let yearMatch = matches.last.flatMap { Range($0.range, in: name) }
    let year = yearMatch.flatMap { Int(String(name[$0]).filter(\.isNumber)) }
    let withoutYear: String
    if let range = yearMatch { withoutYear = String(name[..<range.lowerBound]) }
    else { withoutYear = name }
    let cleaned = withoutYear
        .replacingOccurrences(of: #"[._]+"#, with: " ", options: .regularExpression)
        .replacingOccurrences(of: #"\b(2160p|1080p|720p|480p|web[- ]?dl|bluray|brrip|x264|x265|h\.?(264|265)|hevc|aac|dts)\b.*"#, with: "", options: [.regularExpression, .caseInsensitive])
        .trimmingCharacters(in: .whitespacesAndNewlines)
    return (cleaned.isEmpty ? name : cleaned, year)
}


enum MovieBridgePlaybackState: Equatable {
    case preparing(String)
    case started
    case failed(String)
    case notOwned
}
