import AppKit
import SwiftUI

/// The visible intrinsic content, before any launcher layout allowance is added.
struct LayoutContentBoundsPreference: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? = nil
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}

/// Local composition controls for the Android TV launcher. The preview uses the
/// same normalized coordinate system as the receiver payload.
struct LayoutSettingsView: View {
    @ObservedObject var model: CompanionModel

    @State private var draft = LayoutSettings()
    @State private var didHydrate = false
    @State private var weatherPreview: WeatherPreview?
    @State private var weatherPreviews: [String: WeatherPreview] = [:]
    @State private var weatherPreviewCacheUnit: WeatherUnit?
    @State private var loadingWeatherLocationIDs = Set<String>()
    @State private var isLoadingWeather = false
    @State private var inlineWeatherContentSize: LayoutSize?
    @AppStorage("glassTV.layoutFrameStyle") private var frameStyleID = TVFrameStyle.samsung.rawValue

    private var frameStyle: TVFrameStyle { TVFrameStyle(rawValue: frameStyleID) ?? .samsung }
    private var effectiveShortcutStyle: ShortcutStyle {
        draft.effectiveShortcutStyle(fallback: model.apps.shortcutStyle)
    }
    private var selectedWeatherLocation: WeatherLocation? {
        model.weather.locations.first { $0.id == model.weather.selectedLocationId } ?? model.weather.locations.first(where: \.enabled)
    }
    private var widgetWeatherSettings: WeatherSettings {
        var settings = model.weather
        settings.style = draft.weatherStyle
        return settings
    }
    private var weatherLocations: [WeatherLocation] { widgetWeatherSettings.widgetLocations }
    /// Use the active Portrait-card count for geometry mutations. This remains
    /// one until side-by-side locations are enabled for a non-inline widget.
    private var portraitLocationCount: Int {
        guard widgetWeatherSettings.usesPortraitCards, widgetWeatherSettings.showAllLocations else { return 1 }
        return max(1, model.weather.locations.filter(\.enabled).count)
    }
    private var usesPortraitCards: Bool { widgetWeatherSettings.usesPortraitCards }
    private var weatherRequestKey: String {
        let ids = weatherLocations.map(\.id).joined(separator: ",")
        return "\(draft.weatherStyle.rawValue)|\(ids)|\(model.weather.unit.rawValue)"
    }
    var body: some View {
        GeometryReader { viewport in
            let controlsWidth = min(320, max(280, viewport.size.width * 0.31))
            let previewWidth = max(220, viewport.size.width - (AppDesign.pageInset * 2) - controlsWidth - 24)
            ScrollView(.vertical) {
                HStack(alignment: .top, spacing: 24) {
                    controlsColumn
                        .frame(width: controlsWidth, alignment: .topLeading)

                    VStack(alignment: .leading, spacing: 12) {
                        Text("TV preview")
                            .font(.headline)
                            .foregroundStyle(.secondary)
                        LayoutTVPreview(
                            model: model,
                            layout: $draft,
                            frameStyle: frameStyle,
                            weatherLocation: selectedWeatherLocation,
                            weatherPreview: weatherPreview,
                            weatherLocations: weatherLocations,
                            portraitLocationCount: portraitLocationCount,
                            weatherPreviews: weatherPreviews,
                            loadingWeatherLocationIDs: loadingWeatherLocationIDs,
                            isLoadingWeather: isLoadingWeather,
                            inlineWeatherContentSize: $inlineWeatherContentSize,
                            onShortcutStyleChange: setShortcutStyle
                        )
                        .frame(width: previewWidth)
                    }
                    .frame(width: previewWidth, alignment: .topLeading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, AppDesign.pageInset)
                .padding(.top, AppDesign.pageTopInset)
                .padding(.bottom, 28)
                .nativeGlassGroup()
            }
        }
        .foregroundStyle(.white)
        .onAppear { draft = model.layoutDraft; didHydrate = true }
        .onChange(of: draft) { value in
            guard didHydrate else { return }
            model.updateLayoutDraft(value)
        }
        .onChange(of: model.layoutDraft) { remote in
            guard remote != draft else { return }
            draft = remote
        }
        .onChange(of: draft.weatherStyle) { style in
            if style != .icon { inlineWeatherContentSize = nil }
        }
        .task(id: weatherRequestKey) { await refreshWeatherPreview() }
    }

    private var controlsColumn: some View {
        VStack(alignment: .leading, spacing: AppDesign.sectionGap) {
            PageHeading("Layout", subtitle: "Arrange your TV layout.")

            Button { model.applyLayout() } label: {
                HStack(spacing: 8) {
                    Icon("upload", fallback: "Update").frame(width: 17, height: 17)
                    Text("Update TV")
                }
            }
            .nativeGlassButton()
            .frame(maxWidth: .infinity, alignment: .leading)
            .disabled(!model.isConnected || model.isWorking || !model.layoutHasChanges)

            controlsPanel
            footer
        }
    }

    private var controlsPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 10) {
                Text("Preview")
                    .font(.headline)
                Toggle("Show Wi-Fi shortcut", isOn: Binding(get: { draft.wifiEnabled }, set: { draft.wifiEnabled = $0 }))
                    .toggleStyle(.switch)
                Picker("Preview frame", selection: $frameStyleID) {
                    ForEach(TVFrameStyle.allCases) { style in Text(style.title).tag(style.rawValue) }
                }
                .pickerStyle(.menu)
                .help("Changes the preview appearance only.")
                Button {
                    draft.resetPositions()
                } label: {
                    HStack(spacing: 8) {
                        Icon("refresh", fallback: "Reset").frame(width: 17, height: 17)
                        Text("Reset positions")
                    }
                }
                .nativeGlassButton()
            }

            Divider()

            VStack(alignment: .leading, spacing: 10) {
                Text("Positioning")
                    .font(.headline)
                Toggle("Snap to edges", isOn: Binding(
                    get: { draft.effectiveSnapToEdges },
                    set: { LayoutGeometry.setSnapping($0, in: &draft, appCount: model.apps.layoutShortcutCount, daily: model.weather.showDaily, weatherLocationCount: portraitLocationCount, weatherPortraitCards: usesPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, shortcutStyle: effectiveShortcutStyle) }
                ))
                    .toggleStyle(.switch)
                    .help("Snap again whenever enabled. Drag near a corner to align both edges; occupied corners keep nearby controls separate.")
                labeledSlider(
                    "Edge padding",
                    value: Binding(
                        get: { draft.effectivePadding },
                        set: { LayoutGeometry.setPadding($0, in: &draft, appCount: model.apps.layoutShortcutCount, daily: model.weather.showDaily, weatherLocationCount: portraitLocationCount, weatherPortraitCards: usesPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, shortcutStyle: effectiveShortcutStyle) }
                    ),
                    range: 0...0.10,
                    step: 0.005,
                    display: Text(draft.effectivePadding, format: .percent.precision(.fractionLength(1)))
                )
                .accessibilityLabel("Edge padding")
                .help("Equal space from all four screen edges. Objects always stay inside this area.")
                Button("Align top row") {
                    LayoutGeometry.alignTopRow(in: &draft, appCount: model.apps.layoutShortcutCount, daily: model.weather.showDaily, weatherLocationCount: portraitLocationCount, weatherPortraitCards: usesPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, shortcutStyle: effectiveShortcutStyle)
                }
                .nativeGlassButton()
                .disabled(LayoutGeometry.topRowItems(draft, appCount: model.apps.layoutShortcutCount, daily: model.weather.showDaily, weatherLocationCount: portraitLocationCount, weatherPortraitCards: usesPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, shortcutStyle: effectiveShortcutStyle).count < 2)
                .help("Line up the vertical centers of items in the upper third of the screen. Keeps their horizontal positions and edge padding.")
            }

            Divider()

            VStack(alignment: .leading, spacing: 10) {
                Text("Shortcuts")
                    .font(.headline)
                Picker("Shortcut style", selection: shortcutStyleBinding) {
                    Text("Minimal pills").tag(ShortcutStyle.minimal)
                    Text("Colorful cards").tag(ShortcutStyle.cards)
                }
                .pickerStyle(.radioGroup)
                .horizontalRadioGroupLayout()
                .labelsHidden()
                .accessibilityLabel("Shortcut style")

                labeledSlider(
                    "App icon size",
                    value: Binding(
                        get: { draft.effectiveAppIconScale },
                        set: { LayoutGeometry.setAppIconScale($0, in: &draft, appCount: model.apps.layoutShortcutCount, shortcutStyle: effectiveShortcutStyle) }
                    ),
                    range: 0.5...1.5,
                    step: 0.05,
                    display: Text("\(draft.effectiveAppIconScale.formatted(.number.precision(.fractionLength(2))))×")
                )
                .accessibilityLabel("App icon size")
                .help("Resizes every shortcut in the TV app row while preserving its safe-area edge anchors.")

                Toggle("Custom app spacing", isOn: Binding(
                    get: { draft.appSpacingEnabled ?? false },
                    set: { LayoutGeometry.setAppSpacingEnabled($0, in: &draft, appCount: model.apps.layoutShortcutCount, shortcutStyle: effectiveShortcutStyle) }
                ))
                    .toggleStyle(.switch)
                    .help("Use a custom gap between TV app shortcuts. Turning this off restores the current default gap without discarding the slider value.")
                if draft.appSpacingEnabled ?? false {
                    labeledSlider(
                        "App spacing",
                        value: Binding(
                            get: { min(3, max(0, draft.appSpacingScale ?? 1)) },
                            set: { LayoutGeometry.setAppSpacingScale($0, in: &draft, appCount: model.apps.layoutShortcutCount, shortcutStyle: effectiveShortcutStyle) }
                        ),
                        range: 0...3,
                        step: 0.05,
                        display: Text(draft.effectiveAppSpacingScale, format: .percent.precision(.fractionLength(0)))
                    )
                    .accessibilityLabel("App spacing")
                    .help("Sets the gap between app buttons from touching to three times the default, without resizing the buttons.")
                }
            }

            Divider()
            Text(deliveryMessage)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    private func labeledSlider<Display: View>(
        _ title: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        step: Double,
        display: Display
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(title)
                Spacer(minLength: 8)
                display
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            Slider(value: value, in: range, step: step)
        }
    }

    private var shortcutStyleBinding: Binding<ShortcutStyle> {
        Binding(get: { effectiveShortcutStyle }, set: setShortcutStyle)
    }

    private func setShortcutStyle(_ style: ShortcutStyle) {
        let currentStyle = effectiveShortcutStyle
        guard currentStyle != style else { return }
        LayoutGeometry.setAppShortcutStyle(style, in: &draft, appCount: model.apps.layoutShortcutCount, currentStyle: currentStyle)
        draft.shortcutStyle = style
        draft.shortcutStyleExplicitlySet = true
    }

    private var footer: some View {
        HStack(spacing: 10) {
            Icon("info", fallback: "Info").frame(width: 17, height: 17).foregroundStyle(.secondary)
            Text("Drag objects to position them. Drag clock or inline weather corners to resize. Right-click weather to change its style. Wi-Fi opens TV network settings.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private var deliveryMessage: String {
        if !model.layoutSyncMessage.isEmpty { return model.layoutSyncMessage }
        if model.isConnected { return model.layoutHasChanges ? "Draft ready to update" : "Layout is up to date" }
        return model.isPaired ? "Connect to update TV" : "Pair with TV to update"
    }

    @MainActor
    private func refreshWeatherPreview() async {
        let locations = weatherLocations
        guard !locations.isEmpty else {
            weatherPreview = nil
            weatherPreviews = [:]
            loadingWeatherLocationIDs = []
            isLoadingWeather = false
            return
        }
        let requestedKey = weatherRequestKey
        let selectedID = selectedWeatherLocation?.id
        if weatherPreviewCacheUnit != model.weather.unit {
            weatherPreviews = [:]
            weatherPreviewCacheUnit = model.weather.unit
        }
        weatherPreview = selectedID.flatMap { weatherPreviews[$0] }
        loadingWeatherLocationIDs = Set(locations.map(\.id))
        isLoadingWeather = true
        for location in locations {
            do {
                let result = try await WeatherProvider().preview(for: location, unit: model.weather.unit)
                guard !Task.isCancelled, requestedKey == weatherRequestKey else { return }
                weatherPreviews[location.id] = result
                if location.id == selectedID { weatherPreview = result }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, requestedKey == weatherRequestKey else { return }
            }
            guard requestedKey == weatherRequestKey else { return }
            loadingWeatherLocationIDs.remove(location.id)
        }
        if requestedKey == weatherRequestKey {
            loadingWeatherLocationIDs = []
            isLoadingWeather = false
        }
    }
}

private struct LayoutTVPreview: View {
    @ObservedObject var model: CompanionModel
    @Binding var layout: LayoutSettings
    let frameStyle: TVFrameStyle
    let weatherLocation: WeatherLocation?
    let weatherPreview: WeatherPreview?
    let weatherLocations: [WeatherLocation]
    let portraitLocationCount: Int
    let weatherPreviews: [String: WeatherPreview]
    let loadingWeatherLocationIDs: Set<String>
    let isLoadingWeather: Bool
    @Binding var inlineWeatherContentSize: LayoutSize?
    let onShortcutStyleChange: (ShortcutStyle) -> Void

    private var asset: TVFrameAsset { TVFrameCatalog.asset(for: frameStyle) }

    var body: some View {
        GeometryReader { proxy in
            let screen = asset.screenRect
            let screenFrame = CGRect(x: proxy.size.width * screen.minX, y: proxy.size.height * screen.minY, width: proxy.size.width * screen.width, height: proxy.size.height * screen.height)
            ZStack(alignment: .topLeading) {
                frameImage.frame(width: proxy.size.width, height: proxy.size.height).allowsHitTesting(false)
                LayoutTVScreen(model: model, layout: $layout, inlineWeatherContentSize: $inlineWeatherContentSize, onShortcutStyleChange: onShortcutStyleChange, weatherLocation: weatherLocation, weatherPreview: weatherPreview, weatherLocations: weatherLocations, portraitLocationCount: portraitLocationCount, weatherPreviews: weatherPreviews, loadingWeatherLocationIDs: loadingWeatherLocationIDs, isLoadingWeather: isLoadingWeather)
                    .frame(width: screenFrame.width, height: screenFrame.height)
                    .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
                    .offset(x: screenFrame.minX, y: screenFrame.minY)
            }
        }
        // The frame is illustrative; the contained launcher viewport is always 16:9.
        .aspectRatio((16.0 / 9.0) * asset.screenHeight / asset.screenWidth, contentMode: .fit)
        .accessibilityIdentifier("tvLauncherLayoutPreview")
    }

    @ViewBuilder private var frameImage: some View {
        if let url = Bundle.main.url(forResource: asset.fileName, withExtension: nil, subdirectory: "layout-frames"),
           let image = NSImage(contentsOf: url) {
            Image(nsImage: image).resizable()
        }
    }
}

private struct LayoutTVScreen: View {
    @ObservedObject var model: CompanionModel
    @Binding var layout: LayoutSettings
    @Binding var inlineWeatherContentSize: LayoutSize?
    let onShortcutStyleChange: (ShortcutStyle) -> Void
    let weatherLocation: WeatherLocation?
    let weatherPreview: WeatherPreview?
    let weatherLocations: [WeatherLocation]
    let portraitLocationCount: Int
    let weatherPreviews: [String: WeatherPreview]
    let loadingWeatherLocationIDs: Set<String>
    let isLoadingWeather: Bool

    private var enabledApps: [AppShortcutDefinition] {
        AppShortcutCatalog.definitions(for: model.apps)
    }

    /// Stable canvas allocation preserves the chosen icon scale while its visible
    /// glyph-and-temperature bounds feed the separate drag geometry below.
    private var weatherRenderSize: LayoutSize {
        LayoutGeometry.size(for: .weather, appCount: enabledApps.count, weatherStyle: layout.weatherStyle, daily: model.weather.showDaily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: widgetSettings.usesPortraitCards, weatherIconScale: layout.effectiveWeatherIconScale, safeAreaPadding: layout.effectivePadding)
    }
    private var weatherSize: LayoutSize {
        LayoutGeometry.size(for: .weather, appCount: enabledApps.count, weatherStyle: layout.weatherStyle, daily: model.weather.showDaily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: widgetSettings.usesPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, weatherIconScale: layout.effectiveWeatherIconScale, safeAreaPadding: layout.effectivePadding)
    }
    private var weatherLocationCount: Int { max(1, weatherLocations.count) }
    private var effectiveShortcutStyle: ShortcutStyle {
        layout.effectiveShortcutStyle(fallback: model.apps.shortcutStyle)
    }
    private var appCarousel: LayoutGeometry.AppCarouselMetrics {
        LayoutGeometry.appCarouselMetrics(
            appCount: enabledApps.count,
            appIconScale: layout.effectiveAppIconScale,
            appSpacingScale: layout.effectiveAppSpacingScale,
            safeAreaPadding: layout.effectivePadding,
            appsShowLabels: layout.effectiveAppsShowLabels,
            shortcutStyle: effectiveShortcutStyle
        )
    }
    private var widgetSettings: WeatherSettings {
        var settings = model.weather
        settings.style = layout.weatherStyle
        return settings
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                TVLocalBackdrop(item: model.activePhotos.first)
                LayoutCanvasItem(layout: $layout, element: .clock, size: LayoutGeometry.size(for: .clock, appCount: enabledApps.count, weatherStyle: layout.weatherStyle, daily: model.weather.showDaily, clockScale: layout.effectiveClockScale), screenSize: proxy.size, weatherDaily: model.weather.showDaily, inlineWeatherContentSize: $inlineWeatherContentSize, appCount: enabledApps.count, shortcutStyle: effectiveShortcutStyle) {
                    TimelineView(.periodic(from: .now, by: 30)) { _ in
                        Text(Date.now.formatted(date: .omitted, time: .shortened))
                            .font(.system(size: max(7, proxy.size.width * 0.04375 * layout.effectiveClockScale), weight: .light)).monospacedDigit()
                            .foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.65)
                            .fixedSize()
                            .anchorPreference(key: LayoutContentBoundsPreference.self, value: .bounds) { $0 }
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                    }
                }
                LayoutCanvasItem(layout: $layout, element: .settings, size: LayoutGeometry.size(for: .settings, appCount: enabledApps.count, weatherStyle: layout.weatherStyle, daily: model.weather.showDaily), screenSize: proxy.size, weatherDaily: model.weather.showDaily, inlineWeatherContentSize: $inlineWeatherContentSize, appCount: enabledApps.count, shortcutStyle: effectiveShortcutStyle) {
                    launcherChromeIcon("settings", fallback: "Settings", screenSize: proxy.size)
                }
                if layout.wifiEnabled {
                    LayoutCanvasItem(layout: $layout, element: .wifi, size: LayoutGeometry.size(for: .wifi, appCount: enabledApps.count, weatherStyle: layout.weatherStyle, daily: model.weather.showDaily), screenSize: proxy.size, weatherDaily: model.weather.showDaily, inlineWeatherContentSize: $inlineWeatherContentSize, appCount: enabledApps.count, shortcutStyle: effectiveShortcutStyle) {
                        launcherChromeIcon("wifi", fallback: "Wi-Fi", screenSize: proxy.size)
                    }
                }
                LayoutCanvasItem(layout: $layout, element: .weather, size: weatherSize, renderSize: weatherRenderSize, screenSize: proxy.size, weatherDaily: model.weather.showDaily, weatherLocationCount: portraitLocationCount, weatherPortraitCards: widgetSettings.usesPortraitCards, inlineWeatherContentSize: $inlineWeatherContentSize, appCount: enabledApps.count, shortcutStyle: effectiveShortcutStyle) {
                    weatherWidget(screenSize: proxy.size)
                        .frame(width: CGFloat(weatherRenderSize.width) * proxy.size.width, height: CGFloat(weatherRenderSize.height) * proxy.size.height, alignment: .topLeading)
                        // The icon is vertically centered in its fallback canvas. Move that
                        // canvas up so its visible center stays inside the measured logical box.
                        .offset(y: -inlineWeatherVisualTopInset(screenHeight: proxy.size.height))
                }
                if !enabledApps.isEmpty {
                    LayoutCanvasItem(layout: $layout, element: .apps, size: LayoutGeometry.size(for: .apps, appCount: enabledApps.count, weatherStyle: layout.weatherStyle, daily: model.weather.showDaily, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: effectiveShortcutStyle), screenSize: proxy.size, weatherDaily: model.weather.showDaily, inlineWeatherContentSize: $inlineWeatherContentSize, appCount: enabledApps.count, shortcutStyle: effectiveShortcutStyle, onShortcutStyleChange: onShortcutStyleChange) {
                        LayoutAppRow(apps: enabledApps, screenSize: proxy.size, showLabels: layout.effectiveAppsShowLabels, shortcutStyle: effectiveShortcutStyle, metrics: appCarousel)
                    }
                }
                LayoutSafeAreaGuide(
                    padding: Binding(get: { layout.effectivePadding }, set: {
                        LayoutGeometry.setPadding($0, in: &layout, appCount: enabledApps.count, daily: model.weather.showDaily, weatherLocationCount: portraitLocationCount, weatherPortraitCards: widgetSettings.usesPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, shortcutStyle: effectiveShortcutStyle)
                    }),
                    screenSize: proxy.size
                )
            }
            .coordinateSpace(name: "launcherCanvas")
        }
    }

    private func launcherChromeIcon(_ name: String, fallback: String, screenSize: CGSize) -> some View {
        Icon(name, fallback: fallback)
            .padding(.horizontal, max(2, screenSize.width * 0.006))
            .padding(.vertical, max(2, screenSize.height * 0.008))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// The icon canvas retains its stable rendering allocation, while the
    /// measured glyph becomes the logical top-left drag and alignment bounds.
    private func inlineWeatherVisualTopInset(screenHeight: CGFloat) -> CGFloat {
        guard layout.weatherStyle == .icon else { return 0 }
        return max(0, CGFloat(weatherRenderSize.height - weatherSize.height) * screenHeight / 2)
    }

    @ViewBuilder
    private func weatherWidget(screenSize: CGSize) -> some View {
        GeometryReader { proxy in
            let nominal = nominalWeatherSize
            let scale = proxy.size.width / nominal.width
            let canvas = WeatherPreviewCanvas(
                location: weatherLocation,
                preview: weatherPreview,
                settings: widgetSettings,
                isLoading: isLoadingWeather,
                onCycleArtwork: nil,
                locations: weatherLocations,
                previews: weatherPreviews,
                loadingLocationIDs: loadingWeatherLocationIDs,
                portraitGap: 12
            )
            Group {
                if layout.weatherStyle == .scenic || widgetSettings.usesPortraitCards || layout.weatherStyle == .icon {
                    scaledWeatherCanvas(canvas, nominal: nominal, scale: scale, bounds: proxy.size)
                } else {
                    scaledWeatherCanvas(canvas, nominal: nominal, scale: scale, bounds: proxy.size)
                        .nativeGlass(corner: max(6, proxy.size.height * 0.08))
                }
            }
        }
    }

    private var nominalWeatherSize: CGSize {
        if widgetSettings.usesPortraitCards {
            let nominal = LayoutGeometry.weatherPortraitNominalSize(locationCount: weatherLocationCount, safeAreaPadding: layout.effectivePadding)
            return CGSize(width: nominal.width, height: nominal.height)
        }
        switch layout.weatherStyle {
        case .icon: return CGSize(width: 240, height: 62.4)
        case .compact: return CGSize(width: 520, height: 184)
        case .minimal: return CGSize(width: 600, height: model.weather.showDaily ? 384 : 264)
        case .scenic: return CGSize(width: 600, height: 338)
        }
    }

    private func scaledWeatherCanvas(_ canvas: WeatherPreviewCanvas, nominal: CGSize, scale: CGFloat, bounds: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            canvas
                .frame(width: nominal.width, height: nominal.height)
                .scaleEffect(scale, anchor: .topLeading)
        }
        .frame(width: bounds.width, height: bounds.height, alignment: .topLeading)
        .clipped()
    }
}

/// Shared shortcut rendering for the Layout canvas and Apps appearance preview.
struct LayoutAppRow: View {
    let apps: [AppShortcutDefinition]
    let screenSize: CGSize
    let showLabels: Bool
    let shortcutStyle: ShortcutStyle
    let metrics: LayoutGeometry.AppCarouselMetrics
    @State private var page = 0

    private var pageSize: Int { metrics.capacity }
    private var pageCount: Int { max(1, Int(ceil(Double(apps.count) / Double(pageSize)))) }
    private var displayedPage: Int { min(page, pageCount - 1) }
    private var pageLayout: LayoutGeometry.AppCarouselMetrics.PageLayout {
        metrics.pageLayout(for: displayedPage)
    }
    private var pages: [[AppShortcutDefinition]] {
        stride(from: 0, to: apps.count, by: pageSize).map { start in
            Array(apps.dropFirst(start).prefix(pageSize))
        }
    }
    private var frameWidth: CGFloat { screenSize.width * metrics.rowWidth }
    private var coreFrameWidth: CGFloat { screenSize.width * pageLayout.coreRowWidth }
    private var cardsWidth: CGFloat { screenSize.width * metrics.cardsWidth }
    private var leadingGutterWidth: CGFloat { screenSize.width * pageLayout.leadingGutter }
    private var arrowTargetWidth: CGFloat { screenSize.width * metrics.arrowTargetWidth }
    private var rowHeight: CGFloat { screenSize.height * metrics.rowHeight }
    private var upcomingGhostOffset: CGFloat { screenSize.width * pageLayout.upcomingHintOffset }
    private var upcomingGhostWidth: CGFloat { min(screenSize.width * metrics.tileWidth, max(0, frameWidth - upcomingGhostOffset)) }
    private var upcomingApp: AppShortcutDefinition? {
        guard displayedPage < pageCount - 1 else { return nil }
        return pages[displayedPage + 1].first
    }

    var body: some View {
        ZStack(alignment: .leading) {
            // Native glass surfaces escape an ancestor's clipping layer.
            // Materialize only the current page, so offscreen glass cannot leak.
            HStack(alignment: .top, spacing: screenSize.width * metrics.gap) {
                ForEach(pages.isEmpty ? [] : pages[displayedPage]) { app in
                    shortcut(app)
                }
            }
            .frame(width: cardsWidth, alignment: .leading)
            .clipped()
            .offset(x: leadingGutterWidth)

            if let upcomingApp, upcomingGhostWidth > 0 {
                // A noninteractive material-backed hint remains inside this exact mask.
                // Native glass is intentionally reserved for the active page because it
                // can escape ancestor clipping on current macOS releases.
                shortcut(upcomingApp, isHint: true)
                    .frame(width: upcomingGhostWidth, alignment: .leading)
                    .drawingGroup()
                    .mask(alignment: .leading) {
                        LinearGradient(
                            colors: [.white.opacity(0.38), .clear],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        .frame(width: upcomingGhostWidth)
                    }
                    .offset(x: upcomingGhostOffset)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }

            if displayedPage > 0 {
                carouselArrow("arrow_left", label: "Previous shortcut page") {
                    page = max(0, displayedPage - 1)
                }
                .frame(width: arrowTargetWidth, height: rowHeight)
            }
            if displayedPage < pageCount - 1 {
                HStack {
                    Spacer(minLength: 0)
                    carouselArrow("arrow_right", label: "Next shortcut page") {
                        page = min(pageCount - 1, displayedPage + 1)
                    }
                    .frame(width: arrowTargetWidth, height: rowHeight)
                }
                .frame(width: coreFrameWidth)
            }
        }
        .frame(width: frameWidth, height: rowHeight, alignment: .leading)
        .onChange(of: apps.map(\.id)) { _ in page = min(page, pageCount - 1) }
    }

    private func carouselArrow(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Icon(icon, fallback: label)
                .frame(width: min(13, arrowTargetWidth * 0.48), height: min(13, arrowTargetWidth * 0.48))
                .frame(width: min(40, arrowTargetWidth), height: min(40, arrowTargetWidth))
                .nativeGlass(corner: 20)
                .opacity(0.78)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    @ViewBuilder
    private func shortcut(_ app: AppShortcutDefinition, isHint: Bool = false) -> some View {
        let width = screenSize.width * metrics.tileWidth
        let iconHeight = screenSize.height * (shortcutStyle == .minimal ? 0.12 : 0.17) * metrics.tileScale
        VStack(spacing: 0) {
            ZStack {
                if shortcutStyle == .cards {
                    RoundedRectangle(cornerRadius: iconHeight * 0.10, style: .continuous)
                        .fill(Color(hex: app.colorBackground) ?? Color.black.opacity(0.45))
                    Icon(app.colorIcon, fallback: app.title)
                        .padding(iconHeight * 0.17)
                } else {
                    Icon(app.minimalIcon, fallback: app.title)
                        .padding(screenSize.height * 0.036 * metrics.tileScale)
                }
            }
            .frame(width: width, height: iconHeight)
            .modifier(ShortcutSurface(style: shortcutStyle, corner: iconHeight * (shortcutStyle == .minimal ? 0.5 : 0.10), usesMaterial: isHint))
            if showLabels {
                Text(app.title)
                    .font(.system(size: screenSize.width * 0.012 * metrics.tileScale))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(width: width, height: screenSize.height * 0.047 * metrics.tileScale)
            }
        }
    }
}

private struct ShortcutSurface: ViewModifier {
    let style: ShortcutStyle
    let corner: CGFloat
    var usesMaterial = false

    @ViewBuilder
    func body(content: Content) -> some View {
        if style == .minimal, usesMaterial {
            content.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: corner, style: .continuous))
        }
        else if style == .minimal { content.nativeGlass(corner: corner) }
        else { content.clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous)) }
    }
}

private extension Color {
    init?(hex: String) {
        let value = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard value.count == 6, let components = UInt64(value, radix: 16) else { return nil }
        self.init(red: Double((components >> 16) & 0xFF) / 255, green: Double((components >> 8) & 0xFF) / 255, blue: Double(components & 0xFF) / 255)
    }
}

private struct LayoutCanvasItem<Content: View>: View {
    @Binding var layout: LayoutSettings
    let element: LayoutElement
    let size: LayoutSize
    var renderSize: LayoutSize? = nil
    let screenSize: CGSize
    var weatherDaily: Bool = true
    var weatherLocationCount: Int = 1
    var weatherPortraitCards: Bool = false
    var inlineWeatherContentSize: Binding<LayoutSize?>? = nil
    var appCount: Int = 0
    var shortcutStyle: ShortcutStyle = .minimal
    var onShortcutStyleChange: ((ShortcutStyle) -> Void)? = nil
    @ViewBuilder let content: () -> Content
    @State private var dragStart: LayoutPosition?
    @State private var isHovering = false
    @State private var resizeSelected = false
    @State private var resizeStart: (position: LayoutPosition, scale: Double, base: LayoutSize, offset: LayoutPosition)?
    @GestureState private var isDragging = false

    private var canResize: Bool { element == .clock || element == .apps || (element == .weather && layout.weatherStyle == .icon) }
    private var resizeScale: Double {
        switch element {
        case .clock: return layout.effectiveClockScale
        case .apps: return layout.effectiveAppIconScale
        case .weather: return layout.effectiveWeatherIconScale
        default: return 1
        }
    }
    private var resolved: LayoutPosition { LayoutGeometry.resolvedPosition(layout, element: element, size: size) }
    private var renderedSize: LayoutSize { renderSize ?? size }
    /// Inline Weather’s render allocation is intentionally larger than its glyph;
    /// its interaction frame must use the measured logical size instead.
    private var frameSize: LayoutSize { element == .weather ? size : renderedSize }
    /// Keep the Android-matched `.04 × .06` logical box intact while making tiny
    /// Mac preview controls comfortably draggable.
    private var editorHitPadding: CGFloat {
        switch element {
        case .settings, .wifi: return 6
        default: return 0
        }
    }

    private func recordInlineWeatherSize(_ measured: LayoutSize?) {
        guard element == .weather, layout.weatherStyle == .icon,
              let measured,
              measured.width.isFinite, measured.height.isFinite,
              measured.width > 0, measured.height > 0 else { return }
        let current = inlineWeatherContentSize?.wrappedValue
        guard current.map({ abs($0.width - measured.width) > 0.000_1 || abs($0.height - measured.height) > 0.000_1 }) ?? true else { return }
        DispatchQueue.main.async {
            guard self.element == .weather, self.layout.weatherStyle == .icon else { return }
            self.inlineWeatherContentSize?.wrappedValue = measured
        }
    }

    var body: some View {
        content()
            .frame(width: CGFloat(frameSize.width) * screenSize.width, height: CGFloat(frameSize.height) * screenSize.height, alignment: .topLeading)
            .padding(editorHitPadding)
            .contentShape(Rectangle())
            .onHover { isHovering = $0 }
            .onTapGesture { if canResize { resizeSelected.toggle() } }
            .gesture(DragGesture(minimumDistance: 1, coordinateSpace: .named("launcherCanvas"))
                .updating($isDragging) { _, state, _ in state = true }
                .onChanged { value in
                    if dragStart == nil { dragStart = resolved }
                    guard let start = dragStart else { return }
                    let position = LayoutGeometry.constrained(
                        LayoutPosition(x: start.x + Double(value.translation.width / max(screenSize.width, 1)), y: start.y + Double(value.translation.height / max(screenSize.height, 1))),
                        size: size, padding: layout.effectivePadding)
                    layout.setPosition(position, for: element)
                }
                .onEnded { _ in
                    if layout.effectiveSnapToEdges {
                        layout.setPosition(LayoutGeometry.snappedPosition(layout, element: element, size: size, appCount: appCount, daily: weatherDaily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize?.wrappedValue, shortcutStyle: shortcutStyle), for: element)
                    }
                    dragStart = nil
                }
            )
            .overlayPreferenceValue(LayoutContentBoundsPreference.self) { anchor in
                GeometryReader { proxy in
                    let contentRect = anchor.map { proxy[$0] }
                    let visualRect = CGRect(
                        x: editorHitPadding,
                        y: editorHitPadding,
                        width: CGFloat(frameSize.width) * screenSize.width,
                        height: CGFloat(frameSize.height) * screenSize.height
                    )
                    let rect = canResize ? contentRect ?? visualRect : visualRect
                    let measuredSize = contentRect.map {
                        LayoutSize(width: Double($0.width / max(screenSize.width, 1)), height: Double($0.height / max(screenSize.height, 1)))
                    }
                    ZStack(alignment: .topLeading) {
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .stroke(Color.white.opacity(0.82), lineWidth: 1)
                            .frame(width: rect.width, height: rect.height)
                            .offset(x: rect.minX, y: rect.minY)
                            .opacity(isHovering || isDragging || resizeSelected || resizeStart != nil ? 1 : 0)
                            .allowsHitTesting(false)
                        if canResize {
                            resizeHandles(in: rect)
                                .opacity(isHovering || resizeSelected || resizeStart != nil ? 1 : 0)
                                .allowsHitTesting(isHovering || resizeSelected || resizeStart != nil)
                        }
                        Color.clear
                            .onAppear { recordInlineWeatherSize(measuredSize) }
                            .onChange(of: measuredSize) { value in recordInlineWeatherSize(value) }
                            .allowsHitTesting(false)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Drag \(element.rawValue) position")
            .contextMenu {
                if element == .apps {
                    Button("Show labels") { LayoutGeometry.setAppLabels(true, in: &layout, appCount: appCount, shortcutStyle: shortcutStyle) }
                        .disabled(layout.effectiveAppsShowLabels)
                    Button("Hide labels") { LayoutGeometry.setAppLabels(false, in: &layout, appCount: appCount, shortcutStyle: shortcutStyle) }
                        .disabled(!layout.effectiveAppsShowLabels)
                    if let onShortcutStyleChange {
                        Divider()
                        Menu("Shortcut style") {
                            ForEach(ShortcutStyle.allCases) { style in
                                Button(style.title) { onShortcutStyleChange(style) }
                                    .disabled(style == shortcutStyle)
                            }
                        }
                    }
                }
                if element == .weather {
                    ForEach(WeatherStyle.allCases) { style in
                        Button(style.title) {
                            layout.setWeatherStyle(style)
                            let newSize = LayoutGeometry.size(for: .weather, appCount: 0, weatherStyle: style, daily: weatherDaily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize?.wrappedValue, weatherIconScale: layout.effectiveWeatherIconScale, safeAreaPadding: layout.effectivePadding)
                            let position = LayoutGeometry.resolvedPosition(layout, element: .weather, size: newSize)
                            layout.weather = layout.effectiveSnapToEdges
                                ? LayoutGeometry.nearestEdge(position, size: newSize, padding: layout.effectivePadding)
                                : LayoutGeometry.constrained(position, size: newSize, padding: layout.effectivePadding)
                        }
                    }
                }
            }
            .offset(
                x: CGFloat(resolved.x) * screenSize.width - editorHitPadding,
                y: CGFloat(resolved.y) * screenSize.height - editorHitPadding
            )
    }

    private func resizeHandles(in rect: CGRect) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach(0..<4) { corner in
                let signX: Double = corner % 2 == 0 ? -1 : 1
                let signY: Double = corner < 2 ? -1 : 1
                Circle().fill(.white)
                    .frame(width: 9, height: 9)
                    .frame(width: 20, height: 20)
                    .contentShape(Rectangle())
                    .highPriorityGesture(DragGesture(minimumDistance: 1, coordinateSpace: .named("launcherCanvas"))
                        .onChanged { value in
                            if resizeStart == nil {
                                if element == .apps {
                                    // A paged app row occupies the safe-area viewport. Its tile,
                                    // not that viewport width, is the proportional resize reference.
                                    resizeStart = (resolved, resizeScale, size, LayoutPosition(x: 0, y: 0))
                                } else {
                                    let offset = LayoutPosition(x: Double(rect.minX / screenSize.width), y: Double(rect.minY / screenSize.height))
                                    resizeStart = (
                                        LayoutPosition(x: resolved.x + offset.x, y: resolved.y + offset.y), resizeScale,
                                        LayoutSize(width: Double(rect.width / screenSize.width) / resizeScale, height: Double(rect.height / screenSize.height) / resizeScale),
                                        LayoutPosition(x: offset.x / resizeScale, y: offset.y / resizeScale))
                                }
                            }
                            guard let start = resizeStart else { return }
                            let translation = LayoutPosition(
                                x: Double(value.translation.width / max(1, screenSize.width)),
                                y: Double(value.translation.height / max(1, screenSize.height)))
                            if element == .apps {
                                let nextScale = LayoutGeometry.appIconScaleForResize(
                                    from: start.scale,
                                    style: shortcutStyle,
                                    showsLabels: layout.effectiveAppsShowLabels,
                                    position: start.position,
                                    size: start.base,
                                    cornerX: signX,
                                    cornerY: signY,
                                    translation: translation,
                                    padding: layout.effectivePadding)
                                LayoutGeometry.setAppIconScale(nextScale, in: &layout, appCount: appCount, shortcutStyle: shortcutStyle)
                                let nextSize = LayoutGeometry.size(
                                    for: .apps,
                                    appCount: appCount,
                                    weatherStyle: layout.weatherStyle,
                                    daily: weatherDaily,
                                    appsShowLabels: layout.effectiveAppsShowLabels,
                                    appIconScale: layout.effectiveAppIconScale,
                                    appSpacingScale: layout.effectiveAppSpacingScale,
                                    safeAreaPadding: layout.effectivePadding,
                                    shortcutStyle: shortcutStyle)
                                layout.apps = LayoutGeometry.appRowResizePosition(
                                    position: start.position,
                                    oldSize: start.base,
                                    newSize: nextSize,
                                    cornerX: signX,
                                    cornerY: signY,
                                    padding: layout.effectivePadding)
                                return
                            }
                            let next = LayoutGeometry.resizedElement(
                                base: start.base, position: start.position, scale: start.scale,
                                cornerX: signX, cornerY: signY,
                                translation: translation,
                                padding: layout.effectivePadding)
                            let origin = LayoutPosition(x: next.position.x - start.offset.x * next.scale, y: next.position.y - start.offset.y * next.scale)
                            let scaledSize = LayoutSize(width: start.base.width * next.scale, height: start.base.height * next.scale)
                            if element == .clock { layout.clockScale = next.scale }
                            else { layout.weatherIconScale = next.scale }
                            layout.setPosition(LayoutGeometry.constrained(origin, size: scaledSize, padding: layout.effectivePadding), for: element)
                        }
                        .onEnded { _ in resizeStart = nil; resizeSelected = true })
                    .accessibilityLabel("Resize \(element.rawValue) \(corner < 2 ? "top" : "bottom") \(corner % 2 == 0 ? "left" : "right")")
                    .help(element == .apps ? "Drag to resize every shortcut proportionally." : "Drag to resize proportionally.")
                    .position(x: signX < 0 ? rect.minX : rect.maxX, y: signY < 0 ? rect.minY : rect.maxY)
            }
        }
    }
}

/// An editing guide only. It never appears on the receiver.
private struct LayoutSafeAreaGuide: View {
    @Binding var padding: Double
    let screenSize: CGSize
    @State private var startPadding: Double?

    var body: some View {
        let inset = min(screenSize.width, screenSize.height) * padding
        ZStack(alignment: .topLeading) {
            Rectangle()
                .strokeBorder(.white.opacity(0.55), style: StrokeStyle(lineWidth: 1, dash: [5, 5]))
                .frame(width: max(0, screenSize.width - inset * 2), height: max(0, screenSize.height - inset * 2))
                .offset(x: inset, y: inset)
                .allowsHitTesting(false)
            Capsule()
                .fill(.white)
                .frame(width: 30, height: 4)
                .frame(width: 44, height: 28)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 1)
                    .onChanged { value in
                        if startPadding == nil { startPadding = padding }
                        let proposed = (startPadding ?? padding) + Double(value.translation.height / max(1, min(screenSize.width, screenSize.height)))
                        padding = min(0.10, max(0, proposed))
                    }
                    .onEnded { _ in startPadding = nil })
                .accessibilityLabel("Safe-area padding handle")
                .accessibilityValue(padding.formatted(.percent.precision(.fractionLength(1))))
                .accessibilityAdjustableAction { direction in
                    if direction == .increment { padding = min(0.10, padding + 0.005) }
                    else if direction == .decrement { padding = max(0, padding - 0.005) }
                }
                .help("Drag vertically to set equal padding from the TV screen edges.")
                .offset(x: screenSize.width / 2 - 22, y: max(0, inset - 14))
        }
    }
}

private struct TVLocalBackdrop: View {
    let item: LocalPhoto?
    var body: some View {
        Group {
            if let item, let image = NSImage(contentsOf: item.mediaKind == .video ? VideoProcessor.previewURL(for: item) : ImageProcessor.url(for: item)) {
                Image(nsImage: image).resizable().scaledToFill()
            } else if let url = Bundle.main.url(forResource: "wallpaper", withExtension: "jpg"), let image = NSImage(contentsOf: url) {
                Image(nsImage: image).resizable().scaledToFill()
            } else { Color.black }
        }
        .overlay(Color.black.opacity(0.24)).clipped()
    }
}
