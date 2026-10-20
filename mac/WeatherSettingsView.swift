import AppKit
import SwiftUI

struct WeatherSettingsView: View {
    @ObservedObject var model: CompanionModel

    @State private var settings = WeatherSettings()
    @State private var query = ""
    @State private var results: [WeatherSearchResult] = []
    @State private var previewLocationID: String?
    @State private var preview: WeatherPreview?
    @State private var rowPreviews: [String: WeatherPreview] = [:]
    @State private var loadingLocationIDs = Set<String>()
    @State private var message: String?
    @State private var showSearch = false
    @State private var didLoadSettings = false
    @State private var isSearching = false
    @State private var isLoadingPreview = false
    @State private var searchGeneration = 0
    @State private var previewGeneration = 0
    @State private var locationPreviewGeneration = 0
    @State private var searchTask: Task<Void, Never>?
    @State private var previewTask: Task<Void, Never>?
    @State private var locationPreviewTask: Task<Void, Never>?

    private let provider = WeatherProvider()

    var body: some View {
        ScrollView(.vertical) {
            HStack(alignment: .top, spacing: 24) {
                VStack(alignment: .leading, spacing: 18) {
                    weatherHeader
                    locationsCard
                    widgetStyleCard
                }
                .frame(minWidth: 360, maxWidth: .infinity, alignment: .topLeading)

                VStack(alignment: .leading, spacing: 18) {
                    weatherPreviewCard
                    widgetSettingsCard
                }
                .frame(minWidth: 360, maxWidth: .infinity, alignment: .topLeading)
            }
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .padding(28)
        }
        .foregroundStyle(.white)
        .onAppear { hydrate(from: model.weather) }
        .onDisappear {
            searchTask?.cancel()
            previewTask?.cancel()
            locationPreviewTask?.cancel()
        }
        .onChange(of: model.weather) { remote in
            guard !model.hasPendingWeatherSync, remote != settings else { return }
            hydrate(from: remote)
        }
        .onChange(of: settings) { value in
            guard didLoadSettings else { return }
            model.updateWeather(value)
        }
        .onChange(of: previewKey) { _ in
            guard didLoadSettings else { return }
            clearPreview()
            refreshPreviewIfAvailable()
        }
        .onChange(of: settings.locations) { _ in refreshLocationPreviews() }
        .onChange(of: settings.unit) { _ in refreshLocationPreviews() }
        .onChange(of: settings.style) { _ in refreshLocationPreviews() }
        .onChange(of: settings.portraitCards) { _ in refreshLocationPreviews() }
        .onChange(of: settings.showAllLocations) { _ in refreshLocationPreviews() }
        .onChange(of: query) { value in
            guard value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            searchGeneration += 1
            searchTask?.cancel()
            results = []
            isSearching = false
        }
        .alert("Weather", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil }
        } message: {
            Text(message ?? "")
        }
    }

    private var weatherHeader: some View {
        HStack(spacing: 16) {
            PageHeading("Weather")
            Spacer(minLength: 16)
            Button {
                showSearch.toggle()
            } label: {
                WeatherIconLabel(icon: "add", text: "Add location")
            }
            .nativeGlassButton()
            .disabled(settings.locations.count >= 6)
            .accessibilityHint(settings.locations.count >= 6 ? "You can save up to six locations." : "Search for a city to add.")
        }
    }

    private var locationsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Your locations")
                    .font(.headline)
                Spacer()
                Text("\(settings.locations.count)/6")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if showSearch { searchPanel }

            if settings.locations.isEmpty {
                VStack(spacing: 9) {
                    WeatherIconLabel(icon: "weather_cloud", text: "No locations")
                        .font(.headline)
                    Text("Add a city to configure the TV weather widget.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Button("Add location") { showSearch = true }
                        .nativeGlassButton()
                }
                .frame(maxWidth: .infinity, minHeight: 130)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(settings.locations.enumerated()), id: \.element.id) { index, location in
                        locationRow(location, index: index)
                        if index < settings.locations.count - 1 {
                            Divider().opacity(0.45)
                        }
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .nativeGlass(corner: 18)
    }

    private var searchPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                TextField("Search city", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(search)
                Button("Search", action: search)
                    .nativeGlassButton()
                    .disabled(isSearching || query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Done") {
                    showSearch = false
                    query = ""
                    results = []
                }
                .nativeGlassButton()
            }

            if isSearching {
                ProgressView("Searching locations…")
                    .controlSize(.small)
            }

            ForEach(results) { result in
                let duplicate = settings.locations.contains { $0.id == result.id }
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(result.name)
                        if !result.subtitle.isEmpty {
                            Text(result.subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Button(duplicate ? "Added" : "Add") { add(result) }
                        .nativeGlassButton()
                        .disabled(duplicate || settings.locations.count >= 6)
                }
                .padding(.vertical, 3)
            }
        }
        .padding(.top, 2)
    }

    private func locationRow(_ location: WeatherLocation, index: Int) -> some View {
        HStack(spacing: 12) {
            WeatherDestinationArtwork(location: location, artworkVariant: location.artworkVariant)
                .frame(width: 96, height: 60)

            Button {
                previewLocationID = location.id
            } label: {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) {
                        locationName(location)
                        Spacer(minLength: 6)
                        rowForecast(location)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        locationName(location)
                        rowForecast(location)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Select \(location.name)")

            Toggle("Enable \(location.name)", isOn: enabledBinding(location.id))
                .labelsHidden()
                .toggleStyle(.switch)

            Menu {
                Button("Move earlier") { move(location.id, -1) }
                    .disabled(index == 0)
                Button("Move later") { move(location.id, 1) }
                    .disabled(index == settings.locations.count - 1)
                Divider()
                Button("Remove location", role: .destructive) { remove(location.id) }
            } label: {
                Icon("settings", fallback: "Options")
                    .frame(width: 18, height: 18)
                    .frame(width: 30, height: 30)
            }
            .nativeGlassButton()
            .accessibilityLabel("Options for \(location.name)")
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 4)
        .background(selectionBackground(for: location))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func locationName(_ location: WeatherLocation) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(location.name)
                .font(.system(size: 16, weight: .semibold))
                .lineLimit(1)
            Text(location.country.isEmpty ? "Location details unavailable" : location.country)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private func rowForecast(_ location: WeatherLocation) -> some View {
        if let forecast = rowPreviews[previewCacheKey(for: location)] {
            HStack(spacing: 5) {
                WeatherGlyph(code: forecast.weatherCode, isDay: forecast.isDay)
                    .frame(width: 15, height: 15)
                Text(rowTemperature(forecast))
                    .font(.caption)
                    .monospacedDigit()
            }
            .foregroundStyle(.secondary)
        } else {
            Text("Forecast unavailable")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func selectionBackground(for location: WeatherLocation) -> some View {
        if selectedLocation?.id == location.id {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.accentColor.opacity(0.14))
        } else {
            Color.clear
        }
    }

    private var widgetStyleCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Widget style")
                .font(.headline)
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 132, maximum: 220), spacing: 10)],
                alignment: .leading,
                spacing: 10
            ) {
                ForEach(WeatherStyle.allCases) { style in
                    WeatherStyleChoice(
                        style: style,
                        selected: settings.style == style,
                        location: selectedArtworkLocation
                    ) {
                        settings.style = style
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    private var weatherPreviewCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("TV widget preview")
                    .font(.headline)
                Spacer()
                Menu {
                    Button("Apply to TV") { model.applyWeather() }
                        .disabled(!model.isConnected || model.isWorking)
                    Button("Refresh forecast") { refreshCurrentForecasts() }
                        .disabled(selectedLocation == nil || isLoadingPreview || !loadingLocationIDs.isEmpty)
                } label: {
                    Icon("settings", fallback: "Options")
                        .frame(width: 18, height: 18)
                        .frame(width: 30, height: 30)
                }
                .nativeGlassButton()
            }

            previewCanvas
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    private var previewMinimumHeight: CGFloat {
        switch settings.style {
        case .minimal: return 300
        case .compact: return 190
        case .scenic: return 250
        case .icon: return 62.4
        }
    }

    @ViewBuilder
    private var previewCanvas: some View {
        let canvas = WeatherPreviewCanvas(
            location: selectedArtworkLocation,
            preview: preview,
            settings: settings,
            isLoading: isLoadingPreview,
            onCycleArtwork: cycleSelectedArtwork,
            locations: settings.locations,
            previews: previewsByLocation,
            loadingLocationIDs: loadingLocationIDs
        )
        if settings.usesPortraitCards {
            WeatherPortraitSettingsPreview(count: settings.widgetLocations.count) {
                canvas
            }
        } else if settings.style == .scenic {
            canvas
                .frame(maxWidth: .infinity)
                .aspectRatio(16 / 9, contentMode: .fit)
        } else if settings.style == .icon {
            canvas
                .frame(width: 240, height: previewMinimumHeight, alignment: .leading)
        } else {
            canvas
                .frame(maxWidth: .infinity)
                .frame(minHeight: previewMinimumHeight)
        }
    }

    private var widgetSettingsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Widget settings")
                .font(.headline)
            WeatherToggleRow(title: "Enable weather on TV", isOn: $settings.enabled)
            if !model.weatherSyncMessage.isEmpty {
                Text(model.weatherSyncMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if settings.style != .icon {
                WeatherToggleRow(title: "Show temperature", isOn: $settings.showTemperature)
                contentScaleControl
            }
            if settings.style == .minimal {
                WeatherToggleRow(title: "Show five-day forecast", isOn: $settings.showDaily)
            }
            if settings.style != .icon {
                WeatherToggleRow(title: "Portrait cards", isOn: $settings.portraitCards)
            }
            if settings.usesPortraitCards {
                WeatherToggleRow(title: "Show locations side by side", isOn: $settings.showAllLocations)
            }
            Divider().opacity(0.45)
            WeatherPickerRow(title: "Temperature unit", selection: $settings.unit) {
                ForEach(WeatherUnit.allCases) { unit in
                    Text(unit.title).tag(unit)
                }
            }
            WeatherPickerRow(title: "Cycle locations", selection: $settings.cycleSeconds) {
                ForEach(WeatherCycle.allCases) { cycle in
                    Text(cycle.title).tag(cycle.rawValue)
                }
            }
            .disabled(settings.usesPortraitCards && settings.showAllLocations)
            Text("City search uses GeoNames through Open-Meteo.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    private var contentScaleControl: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Content size")
                Spacer()
                Text(settings.effectiveContentScale.formatted(.percent.precision(.fractionLength(0))))
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            Slider(value: $settings.contentScale, in: 1...1.6, step: 0.05)
                .accessibilityLabel("Weather card content size")
                .accessibilityValue(settings.effectiveContentScale.formatted(.percent.precision(.fractionLength(0))))
        }
    }

    private var selectedLocation: WeatherLocation? {
        settings.locations.first { $0.id == previewLocationID }
            ?? settings.locations.first { $0.id == settings.selectedLocationId }
            ?? settings.locations.first
    }

    private var selectedArtworkLocation: WeatherLocation? {
        selectedLocation ?? settings.locations.first
    }

    private var previewKey: PreviewKey? {
        guard let location = selectedLocation else { return nil }
        return PreviewKey(locationID: location.id, unit: settings.unit)
    }

    private var previewsByLocation: [String: WeatherPreview] {
        Dictionary(uniqueKeysWithValues: settings.locations.compactMap { location in
            rowPreviews[previewCacheKey(for: location)].map { (location.id, $0) }
        })
    }

    private func hydrate(from source: WeatherSettings) {
        didLoadSettings = false
        settings = source
        previewLocationID = source.selectedLocationId ?? source.locations.first?.id
        clearPreview()
        DispatchQueue.main.async {
            didLoadSettings = true
            refreshPreviewIfAvailable()
            refreshLocationPreviews()
        }
    }

    private func enabledBinding(_ id: String) -> Binding<Bool> {
        Binding(
            get: { settings.locations.first(where: { $0.id == id })?.enabled ?? false },
            set: { value in
                guard let index = settings.locations.firstIndex(where: { $0.id == id }) else { return }
                settings.locations[index].enabled = value
                settings.normalize()
            }
        )
    }

    private func add(_ result: WeatherSearchResult) {
        guard settings.locations.count < 6, !settings.locations.contains(where: { $0.id == result.id }) else { return }
        settings.locations.append(
            WeatherLocation(
                id: result.id,
                name: result.name,
                country: result.country,
                latitude: result.latitude,
                longitude: result.longitude,
                enabled: true
            )
        )
        settings.normalize()
        results = []
        query = ""
        showSearch = false
    }

    private func remove(_ id: String) {
        settings.locations.removeAll { $0.id == id }
        settings.normalize()
    }

    private func move(_ id: String, _ offset: Int) {
        guard let index = settings.locations.firstIndex(where: { $0.id == id }),
              settings.locations.indices.contains(index + offset) else { return }
        settings.locations.swapAt(index, index + offset)
    }

    private func cycleSelectedArtwork() {
        guard let location = selectedArtworkLocation,
              let index = settings.locations.firstIndex(where: { $0.id == location.id }),
              let nextVariant = WeatherArtwork.nextArtworkVariant(for: settings.locations[index]) else { return }
        settings.locations[index].artworkVariant = nextVariant
    }

    private func search() {
        let requested = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !requested.isEmpty else { return }
        searchGeneration += 1
        let generation = searchGeneration
        searchTask?.cancel()
        isSearching = true
        results = []
        searchTask = Task {
            do {
                let found = try await provider.search(requested)
                guard !Task.isCancelled, generation == searchGeneration else { return }
                results = found
            } catch is CancellationError {
                return
            } catch {
                guard generation == searchGeneration else { return }
                message = error.localizedDescription
            }
            if generation == searchGeneration { isSearching = false }
        }
    }

    private func clearPreview() {
        previewGeneration += 1
        previewTask?.cancel()
        locationPreviewTask?.cancel()
        previewTask = nil
        locationPreviewTask = nil
        preview = nil
        loadingLocationIDs = []
        isLoadingPreview = false
    }

    private func previewCacheKey(for location: WeatherLocation) -> String {
        "\(location.id)|\(settings.unit.rawValue)"
    }

    private func rowTemperature(_ forecast: WeatherPreview) -> String {
        guard let value = forecast.temperature else { return "—" }
        return "\(value.formatted(.number.precision(.fractionLength(0))))\(forecast.unit)"
    }

    private func refreshLocationPreviews(force: Bool = false) {
        locationPreviewGeneration += 1
        let generation = locationPreviewGeneration
        locationPreviewTask?.cancel()
        let locations = force && settings.usesPortraitCards && settings.showAllLocations
            ? settings.widgetLocations
            : settings.locations
        let unit = settings.unit
        let requestedIDs = Set(locations.map(\.id))
        loadingLocationIDs = Set(locations.filter { force || rowPreviews["\($0.id)|\(unit.rawValue)"] == nil }.map(\.id))
        locationPreviewTask = Task {
            for location in locations where force || rowPreviews["\(location.id)|\(unit.rawValue)"] == nil {
                guard !Task.isCancelled, generation == locationPreviewGeneration, settings.unit == unit else { return }
                do {
                    let value = try await provider.preview(for: location, unit: unit)
                    guard !Task.isCancelled, generation == locationPreviewGeneration, settings.unit == unit else { return }
                    rowPreviews["\(location.id)|\(unit.rawValue)"] = value
                } catch is CancellationError {
                    return
                } catch {
                    guard generation == locationPreviewGeneration else { return }
                }
                guard generation == locationPreviewGeneration else { return }
                loadingLocationIDs.remove(location.id)
            }
            guard generation == locationPreviewGeneration else { return }
            loadingLocationIDs.subtract(requestedIDs)
        }
    }

    private func refreshCurrentForecasts() {
        if settings.usesPortraitCards, settings.showAllLocations {
            refreshLocationPreviews(force: true)
        } else {
            refreshPreviewIfAvailable(force: true)
        }
    }

    private func refreshPreviewIfAvailable(force: Bool = false) {
        guard let location = selectedLocation else { return }
        if !force, preview != nil { return }
        previewGeneration += 1
        let generation = previewGeneration
        let key = previewKey
        previewTask?.cancel()
        preview = nil
        isLoadingPreview = true
        let unit = settings.unit
        previewTask = Task {
            do {
                let value = try await provider.preview(for: location, unit: unit)
                guard !Task.isCancelled, generation == previewGeneration, key == previewKey else { return }
                preview = value
                rowPreviews[previewCacheKey(for: location)] = value
            } catch is CancellationError {
                return
            } catch {
                guard generation == previewGeneration else { return }
                message = error.localizedDescription
            }
            if generation == previewGeneration { isLoadingPreview = false }
        }
    }
}

private struct WeatherPortraitSettingsPreview<Content: View>: View {
    let count: Int
    @ViewBuilder let content: () -> Content

    var body: some View {
        WeatherPortraitSettingsLayout(count: count) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct WeatherPortraitSettingsLayout: Layout {
    let count: Int
    private let gap: CGFloat = 12

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let cardCount = max(1, min(6, count))
        let fallbackWidth = CGFloat(cardCount) * 280 + CGFloat(cardCount - 1) * gap
        let availableWidth = proposal.width ?? fallbackWidth
        let cardWidth = min(280, max(1, (availableWidth - CGFloat(cardCount - 1) * gap) / CGFloat(cardCount)))
        return CGSize(
            width: cardWidth * CGFloat(cardCount) + CGFloat(cardCount - 1) * gap,
            height: cardWidth * 1.5
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let content = subviews.first else { return }
        let size = sizeThatFits(proposal: ProposedViewSize(width: bounds.width, height: nil), subviews: subviews, cache: &cache)
        content.place(
            at: bounds.origin,
            anchor: .topLeading,
            proposal: ProposedViewSize(width: size.width, height: size.height)
        )
    }
}

private struct PreviewKey: Equatable {
    let locationID: String
    let unit: WeatherUnit
}

