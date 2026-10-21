import AppKit
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var model: CompanionModel
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var movies: MoviesModel
    @ObservedObject var movieBridge: MovieLibraryBridge
    let movieServer: MovieHTTPServer
    @State private var importing = false
    @State private var selectedSection: AppSection = .media
    @State private var filter: MediaFilter = .all
    @State private var selectedMediaIDs = Set<UUID>()

    var body: some View {
        ZStack {
            NativeWindowBackdrop().ignoresSafeArea()
            NavigationSplitView {
                navigation
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 250)
            } detail: {
                detailContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .environment(\.colorScheme, .dark)
        .transparentWindowContainer()
        .fileImporter(
            isPresented: $importing,
            // `.data` keeps containers such as MKV visible even when LaunchServices has no
            // specific movie UTI. The importer probes before converting or retaining media.
            allowedContentTypes: [.image, .movie, .mpeg4Movie, .data],
            allowsMultipleSelection: true
        ) { result in
            if case .success(let urls) = result { model.importFiles(urls) }
        }
        .onDrop(of: [.fileURL], isTargeted: nil, perform: importDroppedFiles)
        .onAppear {
            if CaptureRunner.captureSection == "weather" { selectedSection = .weather }
            if CaptureRunner.captureSection == "layout" { selectedSection = .layout }
            movieBridge.start(movies: movies, companion: model, streamServer: movieServer)
            movieBridge.libraryDidChange()
        }
        // The app owns the bridge, so it continues serving a verified TV while this
        // window is inactive or closed, unlike the UI-only status refresh below.
        .onChange(of: model.apps.moviesEnabled) { _ in movieBridge.availabilityDidChange() }
        .onChange(of: model.isConnected) { _ in movieBridge.availabilityDidChange() }
        .onChange(of: movies.libraryRevision) { _ in movieBridge.libraryDidChange() }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.didWakeNotification)) { _ in
            Task { await model.resumeAutomaticConnection() }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else {
                model.setApplicationActive(false)
                return
            }
            model.setApplicationActive(true)
            await model.resumeAutomaticConnection()
            while !Task.isCancelled {
                let delay = model.automaticStatusRefreshDelay
                do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
                catch { return }
                await model.refreshStatus()
            }
        }
        .alert("Glass TV", isPresented: Binding(
            get: { model.presentsErrorAlert && model.errorMessage != nil },
            set: { if !$0 { model.presentsErrorAlert = false } }
        )) {
            Button("OK") { model.errorMessage = nil; model.presentsErrorAlert = false }
        } message: {
            Text(model.errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var detailContent: some View {
        Group {
            switch selectedSection {
            case .media: mediaPage
            case .movies: MoviesView(movies: movies, companion: model, streamServer: movieServer)
            case .apps: AppsSettingsView(model: model, movieBridge: movieBridge)
            case .layout: LayoutSettingsView(model: model)
            case .weather: WeatherSettingsView(model: model)
            case .tv: tvPage
            }
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .nativeGlassGroup()
    }

    private var navigation: some View {
        List(selection: $selectedSection) {
            Section {
                ForEach(AppSection.allCases) { section in
                    HStack(spacing: 12) {
                        Icon(section.icon, fallback: section.title)
                            .frame(width: 22, height: 22)
                        Text(section.title)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                    }
                    .tag(section)
                }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .navigationTitle("Glass TV")
        .safeAreaInset(edge: .bottom, spacing: 0) {
            SidebarTVSwitcher(model: model) {
                selectedSection = .tv
            }
        }
    }

    private var mediaPage: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 20) {
                mediaHeader
                mediaFilters
                mediaGrid
            }
            .frame(minWidth: 360, maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            MediaInspector(model: model, previewItem: previewItem) { connectionControls }
        }
        .padding(.horizontal, AppDesign.pageInset)
        .padding(.top, AppDesign.pageTopInset)
        .padding(.bottom, 24)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !selectedMediaIDs.isEmpty {
                mediaFooter
                    .padding(.horizontal, AppDesign.pageInset)
                    .padding(.bottom, 20)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.16), value: selectedMediaIDs.isEmpty)
        .onChange(of: filter) { _ in selectedMediaIDs.removeAll() }
        .onChange(of: model.photos.map(\.id)) { ids in selectedMediaIDs.formIntersection(Set(ids)) }
    }

    private var mediaHeader: some View {
        HStack(alignment: .center, spacing: 16) {
            PageHeading("Media", subtitle: "Add media to view on your TV.")
            Spacer(minLength: 8)
            Button { importing = true } label: {
                HStack(spacing: 8) {
                    Icon("add", fallback: "Add").frame(width: 17, height: 17)
                    Text("Add media")
                }
                
            }
            .nativeGlassButton()
            .disabled(model.isWorking)
        }
    }

    private var mediaFilters: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Picker("Media filter", selection: $filter) {
                    ForEach(MediaFilter.allCases) { option in Text(option.title).tag(option) }
                }
                .labelsHidden().pickerStyle(.segmented).fixedSize()
                Spacer()
                Text("\(filteredMedia.count) \(filteredMedia.count == 1 ? "item" : "items")")
                    .font(.system(size: 13)).monospacedDigit().foregroundStyle(.secondary)
            }
            if !model.importMessage.isEmpty {
                Text(model.importMessage).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var mediaGrid: some View {
        if filteredMedia.isEmpty {
            VStack(spacing: 12) {
                Icon("image", fallback: "Media").frame(width: 38, height: 38)
                Text("Add your photos and videos").font(.headline)
                Text("Choose media to create your TV rotation.").foregroundStyle(.secondary)
                Button("Add media") { importing = true }.nativeGlassButton()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            MediaCollectionView(items: filteredMedia, selection: $selectedMediaIDs,
                onToggleRotation: { model.toggleRotation($0) },
                onSetBackground: { model.setBackground($0) },
                onRemoveBackground: { model.removeBackground($0) },
                activeBackgroundID: model.activeBackgroundID,
                onMove: { model.move($0, offset: $1) })
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var mediaFooter: some View {
        HStack(spacing: 12) {
            Text("\(selectedMediaIDs.count) \(selectedMediaIDs.count == 1 ? "item" : "items") selected")
                .font(.system(size: 14, weight: .medium)).monospacedDigit()
            Button("Deselect") { selectedMediaIDs.removeAll() }
                .nativeGlassButton()
            Spacer(minLength: 8)
            if model.photos.contains(where: { selectedMediaIDs.contains($0.id) && !$0.inRotation }) {
                Button("Include in rotation") { updateSelectedRotation(included: true) }
                    .nativeGlassButton()
            }
            if model.photos.contains(where: { selectedMediaIDs.contains($0.id) && $0.inRotation }) {
                Button("Remove from rotation") { updateSelectedRotation(included: false) }
                    .nativeGlassButton()
            }
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 64)
        .nativeGlass(corner: 22)
    }

    @ViewBuilder
    private var connectionControls: some View {
        if let fingerprint = model.discoveredPin {
            Text("Confirm the TV fingerprint, then enter the TV code.").font(.caption).foregroundStyle(.secondary)
            Text(groupedFingerprint(fingerprint)).font(.system(size: 10, design: .monospaced)).textSelection(.enabled)
            TextField("Enter TV code", text: $model.pairingCode).textFieldStyle(.roundedBorder)
            Button("Connect TV") { model.confirmPairing() }.nativeGlassButton().disabled(model.isWorking)
        } else if !model.isPaired {
            Text("On the TV, open Settings, then Pair Mac.").font(.caption).foregroundStyle(.secondary)
            Button("Connect TV") { selectedSection = .tv }.nativeGlassButton().disabled(model.isWorking)
        } else {
            Text(model.connectionMessage).font(.caption).foregroundStyle(.secondary)
            HStack {
                Button(model.connectionActivity != .idle ? "Checking TV…" : (model.isConnected ? "Check connection" : "Connect")) { model.connect() }.nativeGlassButton().disabled(model.isWorking)
                Button("Re-pair TV") { model.beginRePairing() }.nativeGlassButton().disabled(model.isWorking)
            }
        }
    }

    private var tvPage: some View {
        TVSettingsView(model: model, previewItem: previewItem)

    }

    private var filteredMedia: [LocalPhoto] {
        switch filter {
        case .all: model.photos
        case .photos: model.photos.filter { $0.mediaKind == .photo }
        case .videos: model.videos
        }
    }

    private var previewItem: LocalPhoto? {
        if let selected = model.photos.first(where: { selectedMediaIDs.contains($0.id) }) { return selected }
        return model.activePhotos.first
    }

    private func updateSelectedRotation(included: Bool) {
        for item in model.photos where selectedMediaIDs.contains(item.id) && item.inRotation != included {
            model.toggleRotation(item)
        }
        selectedMediaIDs.removeAll()
    }

    private func importDroppedFiles(_ providers: [NSItemProvider]) -> Bool {
        for provider in providers {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                guard let data = item as? Data,
                      let url = URL(dataRepresentation: data, relativeTo: nil) else { return }
                DispatchQueue.main.async { model.importFiles([url]) }
            }
        }
        return !providers.isEmpty
    }
}

struct TVPreviewFrame: View {
    let item: LocalPhoto?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                if let frameURL = Bundle.main.url(forResource: "sony-preview", withExtension: "png"),
                   let frame = NSImage(contentsOf: frameURL) {
                    Image(nsImage: frame).resizable().scaledToFit()
                }
                TVScreenContent(url: previewURL)
                    .frame(width: proxy.size.width * 0.8987, height: proxy.size.height * 0.7322)
                    .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
                    .position(x: proxy.size.width * 0.4994, y: proxy.size.height * 0.4629)
            }
        }
        .aspectRatio(1619 / 971, contentMode: .fit)
    }

    private var previewURL: URL? {
        if let item { return item.mediaKind == .video ? VideoProcessor.previewURL(for: item) : ImageProcessor.url(for: item) }
        return Bundle.main.url(forResource: "wallpaper", withExtension: "jpg")
    }
}

private struct TVScreenContent: View {
    let url: URL?
    var body: some View {
        Group {
            if let url, let image = NSImage(contentsOf: url) {
                Image(nsImage: image).resizable().scaledToFill()
            } else { Color.black.opacity(0.35) }
        }
    }
}


enum AppSection: CaseIterable, Identifiable, Hashable { case media, movies, apps, layout, tv, weather
    var id: Self { self }
    var title: String { switch self { case .media: "Media"; case .movies: "Movies"; case .apps: "Apps"; case .layout: "Layout"; case .tv: "TV"; case .weather: "Weather" } }
    var icon: String { switch self { case .media: "image"; case .movies: "movies"; case .apps: "apps"; case .layout: "settings"; case .tv: "tv"; case .weather: "weather_cloud" } }
}

private enum MediaFilter: String, CaseIterable, Identifiable { case all, photos, videos
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
}

struct Icon: View {
    let name: String
    let fallback: String
    init(_ name: String, fallback: String) { self.name = name; self.fallback = fallback }
    var body: some View {
        if let url = Bundle.main.url(forResource: name, withExtension: "png", subdirectory: "icons"), let image = NSImage(contentsOf: url) {
            Image(nsImage: image).resizable().scaledToFit()
        } else { Text(fallback).font(.caption) }
    }
}

private struct NativeWindowBackdrop: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = .underWindowBackground
        view.blendingMode = .behindWindow
        view.state = .followsWindowActiveState
        return view
    }

    func updateNSView(_ view: NSVisualEffectView, context: Context) {
        view.window?.isOpaque = false
        view.window?.backgroundColor = .clear
    }
}

