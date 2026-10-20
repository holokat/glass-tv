import AppKit
import SwiftUI

struct MoviesView: View {
    @ObservedObject var movies: MoviesModel
    @ObservedObject var companion: CompanionModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let streamServer: MovieHTTPServer
    @State private var query = ""
    @State private var collection: MovieCollection = .all
    @State private var showsInfo = false
    @State private var seekPosition = 0.0
    @State private var hoveredMovieID: String?
    @FocusState private var focusedMovieAction: String?
    @FocusState private var focusedMoviePoster: String?

    private var filtered: [MovieItem] {
        movies.state.visibleMovies(in: collection, query: query)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: AppDesign.sectionGap) {
            PageHeading("Movies")
            HStack(spacing: 12) {
                HStack(spacing: 10) {
                    Icon("search", fallback: "Search").frame(width: 18, height: 18)
                    TextField("Search your movies", text: $query)
                        .textFieldStyle(.plain)
                }
                .padding(.horizontal, 14)
                .frame(maxWidth: 480, minHeight: 42)
                .nativeGlass(corner: 18)
                Spacer()
                Button { movies.chooseFolder() } label: {
                    HStack(spacing: 8) {
                        Icon("folder", fallback: "Folder").frame(width: 18, height: 18)
                        Text(movies.hasFolder ? "Change folder" : "Choose folder")
                    }
                }
                .nativeGlassButton()
                Button { movies.refresh() } label: { Icon("refresh", fallback: "Refresh").frame(width: 18, height: 18) }
                    .nativeGlassButton()
                    .disabled(!movies.hasFolder || movies.isScanning)
            }
            HStack {
                Picker("Movie collection", selection: $collection) {
                    ForEach(MovieCollection.allCases) { tab in Text(tab.rawValue).tag(tab) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 280)
                Spacer()
                Text("\(filtered.count) \(filtered.count == 1 ? "movie" : "movies")")
                    .font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
            if let current = movies.playingStatus, let currentMovie = movies.movies.first(where: { $0.id == current.movieId }) {
                playbackBanner(movie: currentMovie, status: current)
            } else if let resume = filtered.compactMap({ movie -> (MovieItem, MovieProgress)? in movies.progress(for: movie).map { (movie, $0) } }).max(by: { $0.1.updatedAt < $1.1.updatedAt }), resume.1.positionMs > 0, resume.1.positionMs < resume.1.durationMs {
                resumeBanner(movie: resume.0, progress: resume.1)
            }
            content
        }
        .padding(.horizontal, AppDesign.pageInset)
        .padding(.top, AppDesign.pageTopInset)
        .padding(.bottom, AppDesign.pageInset)
        .sheet(isPresented: $showsInfo) {
            if let movie = movies.selectedMovie { MovieInfoView(movie: movie, posterURL: movies.posterURL(for: movie)) }
        }
        .alert("Movies", isPresented: Binding(get: { movies.errorMessage != nil }, set: { if !$0 { movies.errorMessage = nil } })) {
            Button("OK") { movies.errorMessage = nil }
        } message: { Text(movies.errorMessage ?? "") }
        .onChange(of: movies.playingStatus?.positionMs) { value in seekPosition = Double(value ?? 0) }
    }

    @ViewBuilder private var content: some View {
        if movies.isScanning {
            VStack(spacing: 12) { ProgressView(); Text("Scanning \(movies.folderName ?? "folder")…").foregroundStyle(.secondary) }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if !movies.hasFolder {
            MoviesEmptyState(icon: "folder", title: "Choose a movie folder", message: "Movies stay in their original folder and stream directly to your TV.", action: "Choose folder", handler: movies.chooseFolder)
        } else if movies.movies.isEmpty {
            MoviesEmptyState(icon: "movies", title: "No readable movies found", message: "Choose a folder with video files that ffprobe can read, then refresh.", action: "Refresh", handler: movies.refresh)
        } else if filtered.isEmpty {
            if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                MoviesEmptyState(icon: "search", title: "No matching movies", message: "Try another search in \(collection.rawValue).", action: "Clear search", handler: { query = "" })
            } else {
                VStack(spacing: 10) {
                    Text(collection == .favorites ? "No favorites yet" : (collection == .watched ? "No watched movies yet" : "All caught up"))
                        .font(.title3.weight(.semibold))
                    Text(collection == .favorites ? "Select the heart on a movie to keep it here." : (collection == .watched ? "Movies you mark as watched appear here." : "Your movies are in Watched. Mark one as unwatched to return it here."))
                        .foregroundStyle(.secondary).multilineTextAlignment(.center)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            ScrollView {
                if !movies.metadataStatus.isEmpty { Text(movies.metadataStatus).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading) }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 210), spacing: 16)], spacing: 20) {
                    ForEach(filtered) { movie in movieTile(movie) }
                }
            }
        }
    }

    private func movieTile(_ movie: MovieItem) -> some View {
        let isFavorite = movies.isFavorite(movie)
        let isWatched = movies.isWatched(movie)
        let actionsVisible = hoveredMovieID == movie.id || focusedMoviePoster == movie.id || focusedMovieAction == "favorite:\(movie.id)" || focusedMovieAction == "watched:\(movie.id)"
        return VStack(alignment: .leading, spacing: 10) {
            ZStack(alignment: .topTrailing) {
                Button {
                    movies.selectedID = movie.id
                    movies.startPlayback(movie: movie, companion: companion, streamServer: streamServer)
                } label: {
                    MovieArtwork(url: movies.posterURL(for: movie))
                        .frame(height: 230)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(!companion.isConnected || companion.isWorking || movie.decodeError != nil)

                if actionsVisible {
                    HStack(spacing: 8) {
                        movieActionButton(
                            icon: "favorite",
                            fallback: "Favorite",
                            isSelected: isFavorite,
                            label: isFavorite ? "Remove \(movie.displayTitle) from favorites" : "Favorite \(movie.displayTitle)",
                            value: isFavorite ? "Favorited" : "Not favorited",
                            help: isFavorite ? "Remove from favorites" : "Move to the top and add to Favorites"
                        ) {
                            movies.toggleFavorite(movie)
                        }
                        .focused($focusedMovieAction, equals: "favorite:\(movie.id)")
                        movieActionButton(
                            icon: "check",
                            fallback: "Watched",
                            isSelected: isWatched,
                            label: "\(isWatched ? "Mark as unwatched:" : "Mark as watched:") \(movie.displayTitle)",
                            value: isWatched ? "Watched" : "Unwatched",
                            help: isWatched ? "Mark as unwatched" : "Mark as watched"
                        ) {
                            movies.toggleWatched(movie)
                        }
                        .focused($focusedMovieAction, equals: "watched:\(movie.id)")
                    }
                    .padding(10)
                    .transition(.opacity)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .focusable()
            .focused($focusedMoviePoster, equals: movie.id)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.12), value: actionsVisible)
            .onHover { isHovering in
                if isHovering { hoveredMovieID = movie.id }
                else if hoveredMovieID == movie.id { hoveredMovieID = nil }
            }
            Text(movie.displayTitle).lineLimit(1).foregroundStyle(.primary)
            HStack {
                if let year = movie.displayYear { Text(String(year)).font(.caption).foregroundStyle(.secondary) }
                else if let duration = movie.durationMs, duration > 0 { Text("\(max(1, duration / 60_000)) min").font(.caption).foregroundStyle(.secondary) }
                if movie.decodeError != nil { Text("Unavailable").font(.caption).foregroundStyle(.secondary) }
            }
        }
        .contextMenu {
            Button("Play on TV") { movies.startPlayback(movie: movie, companion: companion, streamServer: streamServer) }
                .disabled(!companion.isConnected || companion.isWorking || movie.decodeError != nil)
            Button(movies.isFavorite(movie) ? "Remove from favorites" : "Favorite") { movies.toggleFavorite(movie) }
            Button(movies.isWatched(movie) ? "Mark unwatched" : "Mark as watched") { movies.toggleWatched(movie) }
            Divider()
            Button("More info") { movies.selectedID = movie.id; showsInfo = true }
        }
    }

    private func movieActionButton(
        icon: String,
        fallback: String,
        isSelected: Bool,
        label: String,
        value: String,
        help: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Icon(icon, fallback: fallback)
                .frame(width: 14, height: 14)
        }
        .nativeGlassButton(controlSize: .small)
        .fixedSize()
        .tint(isSelected ? Color.accentColor : nil)
        .accessibilityLabel(label)
        .accessibilityValue(value)
        .help(help)
    }

    private func resumeBanner(movie: MovieItem, progress: MovieProgress) -> some View {
        HStack(spacing: 18) {
            MovieArtwork(url: movies.posterURL(for: movie)).frame(width: 150, height: 82).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 5) {
                Text("Continue watching").font(.caption).foregroundStyle(.secondary)
                Text(movie.displayTitle).font(.title3.weight(.semibold))
                ProgressView(value: Double(progress.positionMs), total: Double(progress.durationMs)).frame(maxWidth: 240)
            }
            Spacer()
            Button { movies.startPlayback(movie: movie, companion: companion, streamServer: streamServer) } label: { LabelView(icon: "play", text: "Resume") }
                .nativeGlassButton()
                .disabled(!companion.isConnected || companion.isWorking || movie.decodeError != nil)
            Button { movies.selectedID = movie.id; showsInfo = true } label: { LabelView(icon: "info", text: "More info") }
                .nativeGlassButton()
        }
        .padding(16)
        .nativeGlass(corner: 20)
    }

    private func playbackBanner(movie: MovieItem, status: MovieStatusSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 14) {
                Text(movie.displayTitle).font(.title3.weight(.semibold))
                Text(status.state?.rawValue.capitalized ?? "Preparing").foregroundStyle(.secondary)
                Spacer()
                if status.state == .playing { Button { movies.control(.pause, companion: companion, streamServer: streamServer) } label: { Icon("pause", fallback: "Pause").frame(width: 18, height: 18) }.nativeGlassButton().accessibilityLabel("Pause movie") }
                if status.state == .paused { Button { movies.control(.resume, companion: companion, streamServer: streamServer) } label: { Icon("play", fallback: "Resume").frame(width: 18, height: 18) }.nativeGlassButton().accessibilityLabel("Resume movie") }
                Button { movies.stop(companion: companion, streamServer: streamServer) } label: { Icon("stop", fallback: "Stop").frame(width: 18, height: 18) }.nativeGlassButton().accessibilityLabel("Stop movie")
            }
            if !movies.preparationMessage.isEmpty {
                Text(movies.preparationMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let duration = status.durationMs, duration > 0 {
                Slider(value: $seekPosition, in: 0...Double(duration), onEditingChanged: { editing in
                    if !editing { movies.control(.seek, positionMs: Int64(seekPosition), companion: companion, streamServer: streamServer) }
                })
            }
        }
        .padding(14)
        .nativeGlass(corner: 18)
    }
}

private struct MovieArtwork: View {
    let url: URL?
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                if let url, let image = NSImage(contentsOf: url) {
                    Image(nsImage: image).resizable().scaledToFill().frame(width: proxy.size.width, height: proxy.size.height).clipped()
                } else { Icon("movies", fallback: "Movie").frame(width: 38, height: 38) }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .background(.secondary.opacity(0.12))
        .clipped()
    }
}

private struct MoviesEmptyState: View {
    let icon: String; let title: String; let message: String; let action: String; let handler: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            Icon(icon, fallback: title).frame(width: 44, height: 44)
            Text(title).font(.title3.weight(.semibold))
            Text(message).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 420)
            Button(action, action: handler).nativeGlassButton()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .nativeGlass(corner: 22)
    }
}

private struct LabelView: View {
    let icon: String; let text: String
    var body: some View { HStack(spacing: 8) { Icon(icon, fallback: text).frame(width: 17, height: 17); Text(text) } }
}

private struct MovieInfoView: View {
    @Environment(\.dismiss) private var dismiss
    let movie: MovieItem; let posterURL: URL?
    var body: some View {
        HStack(alignment: .top, spacing: 20) {
            MovieArtwork(url: posterURL).frame(width: 170, height: 250).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(movie.displayTitle).font(.title.weight(.semibold))
                if let year = movie.displayYear { Text(String(year)).foregroundStyle(.secondary) }
                if let overview = movie.metadata?.overview { Text(overview) }
                if !movie.metadata.orEmpty(\.genres).isEmpty { Text(movie.metadata!.genres.joined(separator: " · ")).foregroundStyle(.secondary) }
                if !movie.metadata.orEmpty(\.cast).isEmpty { Text("Cast: \(movie.metadata!.cast.joined(separator: ", "))").foregroundStyle(.secondary) }
                if let issue = movie.decodeError { Text(issue).foregroundStyle(.secondary) }
                if let source = movie.metadata?.sourceURL { Link("View metadata source", destination: source) }
                Spacer()
            }
            }
        }
        .padding(28).frame(minWidth: 580, minHeight: 330)
        .overlay(alignment: .bottomTrailing) { Button("Done") { dismiss() }.nativeGlassButton().padding(20) }
    }
}

private extension Optional where Wrapped == MovieMetadata {
    func orEmpty<T>(_ keyPath: KeyPath<MovieMetadata, [T]>) -> [T] { self?[keyPath: keyPath] ?? [] }
}
