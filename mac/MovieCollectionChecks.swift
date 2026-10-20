import Foundation

/// Isolated fixtures used only by --self-test; never added to the user's library.
enum MovieCollectionChecks {
    static func run() throws {
        let legacy = Data(#"{"folderName":"Test fixture","movies":[{"id":"b","relativePath":"b.mp4","fileName":"b.mp4","title":"Beta","fileSize":1},{"id":"a","relativePath":"a.mp4","fileName":"a.mp4","title":"Alpha","fileSize":1}],"progress":{"b":{"positionMs":100,"durationMs":1000,"updatedAt":0}}}"#.utf8)
        var library = try JSONDecoder().decode(MovieLibraryState.self, from: legacy)
        func ids(_ tab: MovieCollection, _ query: String = "") -> [String] { library.visibleMovies(in: tab, query: query).map(\.id) }
        guard library.favoriteIDs.isEmpty, library.watchedIDs.isEmpty,
              ids(.all) == ["a", "b"] else { throw ProtocolSelfTest.SelfTestError.failed }
        library.favoriteIDs.insert("b")
        guard ids(.all) == ["b", "a"], ids(.favorites) == ["b"],
              ids(.favorites, "ALPHA").isEmpty, ids(.all, "ALPHA") == ["a"] else { throw ProtocolSelfTest.SelfTestError.failed }
        library.watchedIDs.insert("b")
        guard ids(.all) == ["a"], ids(.watched) == ["b"], ids(.favorites) == ["b"] else { throw ProtocolSelfTest.SelfTestError.failed }
        let restored = try JSONDecoder().decode(MovieLibraryState.self, from: JSONEncoder().encode(library))
        guard restored.favoriteIDs == ["b"], restored.watchedIDs == ["b"],
              restored.folderName == "Test fixture", restored.progress["b"]?.positionMs == 100,
              restored.movies.count == 2 else { throw ProtocolSelfTest.SelfTestError.failed }
        library.watchedIDs.remove("b")
        library.favoriteIDs.remove("b")
        guard ids(.all) == ["a", "b"], ids(.favorites).isEmpty, ids(.watched).isEmpty else { throw ProtocolSelfTest.SelfTestError.failed }
    }
}
