import Foundation

struct MovieMetadata: Codable, Hashable {
    var title: String
    var year: Int?
    var overview: String?
    var runtimeMinutes: Int?
    var genres: [String] = []
    var cast: [String] = []
    var directors: [String] = []
    var posterURL: URL?
    var backdropURL: URL?
    var imdbID: String?
    var wikidataID: String?
    var sourceURL: URL?
}

/// Resolves actual file titles against Wikidata, then enriches known IMDb IDs
/// through Stremio's documented Cinemeta metadata endpoint. No file paths,
/// video bytes, TV credentials, or pairing data leave the Mac.
struct MovieMetadataProvider {
    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        session = URLSession(configuration: configuration)
    }

    func lookup(title: String, year: Int?) async throws -> MovieMetadata? {
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.count <= 250 else { return nil }
        try Task.checkCancellation()
        let language = Locale.current.language.languageCode?.identifier ?? "en"
        var hits = try await search(title: title, language: language)
        if language != "en" && !hits.contains(where: { normalized($0.label ?? "") == normalized(title) }) {
            let english = try await search(title: title, language: "en")
            let known = Set(hits.map(\.id))
            hits += english.filter { !known.contains($0.id) }
        }
        guard !hits.isEmpty else { return nil }
        let entities: EntityResponse = try await wikidata([
            "action": "wbgetentities", "ids": hits.prefix(8).map(\.id).joined(separator: "|"),
            "props": "labels|aliases|descriptions|claims", "languages": "en|\(language)",
            "languagefallback": "1"
        ])
        let wanted = normalized(title)
        var candidates: [(String, Entity, String, Int?)] = []
        for hit in hits.prefix(8) {
            guard let entity = entities.entities[hit.id] else { continue }
            let entityTitle = entity.labels?["en"]?.value ?? entity.labels?[language]?.value ?? hit.label ?? title
            let names = [entityTitle, hit.label ?? ""] + (entity.aliases?.values.flatMap { $0.map(\.value) } ?? [])
            guard names.contains(where: { normalized($0) == wanted }) else { continue }
            let description = entity.descriptions?["en"]?.value ?? hit.description ?? ""
            let film = entity.entityIDs("P31").contains("Q11424") || description.localizedCaseInsensitiveContains("film")
            guard film else { continue }
            let releaseYear = entity.firstYear("P577")
            if let year, let releaseYear, abs(year - releaseYear) > 1 { continue }
            candidates.append((hit.id, entity, entityTitle, releaseYear))
        }
        // A title shared by remakes must not silently acquire another film's art.
        if let year {
            let exactYear = candidates.filter { $0.3 == year }
            if !exactYear.isEmpty { candidates = exactYear }
        }
        guard candidates.count == 1, let match = candidates.first else { return nil }
        let imdb = match.1.strings("P345").first(where: { $0.range(of: "^tt[0-9]{7,10}$", options: .regularExpression) != nil })
        var result = MovieMetadata(title: match.2, year: match.3, imdbID: imdb,
                                   wikidataID: match.0, sourceURL: URL(string: "https://www.wikidata.org/wiki/\(match.0)"))
        if let imdb {
            do {
                let details: CinemetaResponse = try await fetch(URL(string: "https://v3-cinemeta.strem.io/meta/movie/\(imdb).json")!)
                if let movie = details.meta, movie.type == nil || movie.type == "movie" {
                    result.title = nonempty(movie.name) ?? result.title
                    result.year = movie.year.flatMap { Int($0.prefix(4)) } ?? result.year
                    result.overview = nonempty(movie.description)
                    result.runtimeMinutes = movie.runtime.flatMap { Int($0.components(separatedBy: CharacterSet.decimalDigits.inverted).first(where: { !$0.isEmpty }) ?? "") }
                    result.genres = movie.genres ?? movie.genre ?? []
                    result.cast = movie.cast ?? []
                    result.directors = movie.director ?? []
                    result.posterURL = artworkURL(movie.poster)
                    result.backdropURL = artworkURL(movie.background)
                }
            } catch is CancellationError { throw CancellationError() }
            catch { /* Verified Wikidata identity remains useful while Cinemeta is offline. */ }
        }
        return result
    }

    private func search(title: String, language: String) async throws -> [SearchHit] {
        let response: SearchResponse = try await wikidata([
            "action": "wbsearchentities", "search": title, "language": language,
            "type": "item", "limit": "8"
        ])
        return response.search
    }

    private func wikidata<T: Decodable>(_ query: [String: String]) async throws -> T {
        var parts = URLComponents(string: "https://www.wikidata.org/w/api.php")!
        var query = query; query["format"] = "json"
        parts.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return try await fetch(parts.url!)
    }

    private func fetch<T: Decodable>(_ url: URL) async throws -> T {
        try Task.checkCancellation()
        var request = URLRequest(url: url)
        request.setValue("GlassTV/1.0 (local movie library)", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode), data.count <= 4 * 1024 * 1024 else {
            throw MetadataError.unavailable
        }
        try Task.checkCancellation()
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func normalized(_ value: String) -> String {
        value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: " ")
    }

    private func nonempty(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }

    private func artworkURL(_ value: String?) -> URL? {
        guard let value, let url = URL(string: value), url.scheme == "https", url.user == nil, url.password == nil,
              let host = url.host?.lowercased(), ["images.metahub.space", "image.tmdb.org", "m.media-amazon.com"].contains(host) else { return nil }
        return url
    }

    private enum MetadataError: Error { case unavailable }
    private struct SearchResponse: Decodable { let search: [SearchHit] }
    private struct SearchHit: Decodable { let id: String; let label: String?; let description: String? }
    private struct EntityResponse: Decodable { let entities: [String: Entity] }
    private struct Term: Decodable { let value: String }
    private struct Entity: Decodable {
        let labels: [String: Term]?
        let aliases: [String: [Term]]?
        let descriptions: [String: Term]?
        let claims: [String: [Claim]]?
        func strings(_ property: String) -> [String] {
            (claims?[property] ?? []).compactMap { $0.mainsnak.datavalue?.value.string }
        }
        func entityIDs(_ property: String) -> [String] {
            (claims?[property] ?? []).compactMap { $0.mainsnak.datavalue?.value.id }
        }
        func firstYear(_ property: String) -> Int? {
            (claims?[property] ?? []).compactMap { $0.mainsnak.datavalue?.value.time }.compactMap { Int($0.dropFirst().prefix(4)) }.min()
        }
    }
    private struct Claim: Decodable { let mainsnak: Snak }
    private struct Snak: Decodable { let datavalue: Value? }
    private struct Value: Decodable { let value: FlexibleValue }
    private struct FlexibleValue: Decodable {
        var string: String?
        var id: String?
        var time: String?
        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let value = try? container.decode(String.self) { string = value; return }
            if let value = try? container.decode([String: FlexibleValue].self) {
                id = value["id"]?.string; time = value["time"]?.string
            }
        }
    }
    private struct CinemetaResponse: Decodable { let meta: CinemetaMovie? }
    private struct CinemetaMovie: Decodable {
        let name: String?
        let type: String?
        let year: String?
        let description: String?
        let runtime: String?
        let genres: [String]?
        let genre: [String]?
        let cast: [String]?
        let director: [String]?
        let poster: String?
        let background: String?
    }
}
