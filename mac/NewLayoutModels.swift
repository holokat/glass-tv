import Foundation
import CoreGraphics

/// A top-left point expressed as a fraction of the receiver's display.
struct LayoutPosition: Codable, Equatable {
    var x: Double
    var y: Double

    func clamped(for size: LayoutSize, inset: Double = 0.01) -> LayoutPosition {
        let maxX = max(inset, 1 - size.width - inset)
        let maxY = max(inset, 1 - size.height - inset)
        return LayoutPosition(x: min(max(x, inset), maxX), y: min(max(y, inset), maxY))
    }
}

struct LayoutSize: Equatable {
    var width: Double
    var height: Double
}

enum LayoutElement: String, CaseIterable, Identifiable {
    case clock, settings, wifi, weather, apps
    var id: String { rawValue }
}

/// Wire contract for the Android launcher. Nil positions retain the receiver's
/// dynamic defaults, which is important when the shortcut count changes.
struct LayoutSettings: Codable, Equatable {
    var version: Int = 1
    var wifiEnabled: Bool = true
    /// Fraction of the screen's shorter edge. Nil preserves older wire documents.
    var safeAreaPadding: Double? = nil
    var effectivePadding: Double { min(0.10, max(0, safeAreaPadding ?? 0.02)) }
    var snapToEdges: Bool? = nil
    var effectiveSnapToEdges: Bool { snapToEdges ?? false }
    var clockScale: Double? = nil
    var effectiveClockScale: Double { min(2, max(0.5, clockScale ?? 1)) }
    var weatherIconScale: Double? = nil
    var effectiveWeatherIconScale: Double { min(2, max(0.5, weatherIconScale ?? 1)) }
    var appsShowLabels: Bool? = nil
    var effectiveAppsShowLabels: Bool { appsShowLabels ?? true }
    /// A shared scale for every shortcut in the launcher row.
    var appIconScale: Double? = nil
    var effectiveAppIconScale: Double { min(1.5, max(0.5, appIconScale ?? 1)) }
    /// Keep custom spacing opt-in so older layouts retain the receiver's current gap.
    var appSpacingEnabled: Bool? = nil
    var appSpacingScale: Double? = nil
    var effectiveAppSpacingScale: Double {
        guard appSpacingEnabled ?? false else { return 1 }
        return min(3, max(0, appSpacingScale ?? 1))
    }
    var clock: LayoutPosition? = nil
    var settings: LayoutPosition? = nil
    var wifi: LayoutPosition? = nil
    var weather: LayoutPosition? = nil
    var apps: LayoutPosition? = nil
    /// This remains local until Update TV, where it is copied to the receiver's
    /// existing weather payload. It is intentionally not included in the wire contract.
    var weatherStyle: WeatherStyle = .minimal
    /// The Weather page can change style independently. Only the Layout context
    /// menu sets this flag, so moving a clock never restores a stale style.
    var weatherStyleExplicitlySet: Bool = false
    /// Shortcut style is owned by AppsSettings on the wire. Layout keeps this
    /// local marker so a context-menu style change can be explicitly submitted
    /// with an otherwise unchanged placement draft.
    var shortcutStyle: ShortcutStyle = .minimal
    var shortcutStyleExplicitlySet: Bool = false
    func effectiveShortcutStyle(fallback: ShortcutStyle) -> ShortcutStyle {
        shortcutStyleExplicitlySet ? shortcutStyle : fallback
    }

    private enum CodingKeys: String, CodingKey {
        case version, wifiEnabled, safeAreaPadding, snapToEdges, clockScale, weatherIconScale, appsShowLabels, appIconScale, appSpacingEnabled, appSpacingScale, clock, settings, wifi, weather, apps, weatherStyle, weatherStyleExplicitlySet, shortcutStyle, shortcutStyleExplicitlySet
    }

    init(version: Int = 1, wifiEnabled: Bool = true, clock: LayoutPosition? = nil, settings: LayoutPosition? = nil, wifi: LayoutPosition? = nil, weather: LayoutPosition? = nil, apps: LayoutPosition? = nil, weatherStyle: WeatherStyle = .minimal, weatherStyleExplicitlySet: Bool = false, shortcutStyle: ShortcutStyle = .minimal, shortcutStyleExplicitlySet: Bool = false) {
        self.version = version
        self.wifiEnabled = wifiEnabled
        self.clock = clock
        self.settings = settings
        self.wifi = wifi
        self.weather = weather
        self.apps = apps
        self.weatherStyle = weatherStyle
        self.weatherStyleExplicitlySet = weatherStyleExplicitlySet
        self.shortcutStyle = shortcutStyle
        self.shortcutStyleExplicitlySet = shortcutStyleExplicitlySet
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decodeIfPresent(Int.self, forKey: .version) ?? 1
        wifiEnabled = try values.decodeIfPresent(Bool.self, forKey: .wifiEnabled) ?? true
        safeAreaPadding = try values.decodeIfPresent(Double.self, forKey: .safeAreaPadding)
        snapToEdges = try values.decodeIfPresent(Bool.self, forKey: .snapToEdges)
        appsShowLabels = try values.decodeIfPresent(Bool.self, forKey: .appsShowLabels)
        appIconScale = try values.decodeIfPresent(Double.self, forKey: .appIconScale)
        appSpacingEnabled = try values.decodeIfPresent(Bool.self, forKey: .appSpacingEnabled)
        appSpacingScale = try values.decodeIfPresent(Double.self, forKey: .appSpacingScale)
        clockScale = try values.decodeIfPresent(Double.self, forKey: .clockScale)
        weatherIconScale = try values.decodeIfPresent(Double.self, forKey: .weatherIconScale)
        if let scale = weatherIconScale, !scale.isFinite || !(0.5...2).contains(scale) {
            throw DecodingError.dataCorruptedError(forKey: .weatherIconScale, in: values, debugDescription: "Invalid weather icon scale")
        }
        if let scale = clockScale, !scale.isFinite || !(0.5...2).contains(scale) {
            throw DecodingError.dataCorruptedError(forKey: .clockScale, in: values, debugDescription: "Invalid clock scale")
        }
        if let scale = appIconScale, !scale.isFinite || !(0.5...1.5).contains(scale) {
            throw DecodingError.dataCorruptedError(forKey: .appIconScale, in: values, debugDescription: "Invalid app icon scale")
        }
        if let scale = appSpacingScale, !scale.isFinite || !(0...3).contains(scale) {
            throw DecodingError.dataCorruptedError(forKey: .appSpacingScale, in: values, debugDescription: "Invalid app spacing scale")
        }
        if let padding = safeAreaPadding, !padding.isFinite || !(0...0.10).contains(padding) {
            throw DecodingError.dataCorruptedError(forKey: .safeAreaPadding, in: values, debugDescription: "Invalid safe-area padding")
        }
        clock = try values.decodeIfPresent(LayoutPosition.self, forKey: .clock)
        settings = try values.decodeIfPresent(LayoutPosition.self, forKey: .settings)
        wifi = try values.decodeIfPresent(LayoutPosition.self, forKey: .wifi)
        weather = try values.decodeIfPresent(LayoutPosition.self, forKey: .weather)
        apps = try values.decodeIfPresent(LayoutPosition.self, forKey: .apps)
        weatherStyle = try values.decodeIfPresent(WeatherStyle.self, forKey: .weatherStyle) ?? .minimal
        weatherStyleExplicitlySet = try values.decodeIfPresent(Bool.self, forKey: .weatherStyleExplicitlySet) ?? false
        shortcutStyle = try values.decodeIfPresent(ShortcutStyle.self, forKey: .shortcutStyle) ?? .minimal
        shortcutStyleExplicitlySet = try values.decodeIfPresent(Bool.self, forKey: .shortcutStyleExplicitlySet) ?? false
    }

    func wireObject() -> [String: Any] {
        var value: [String: Any] = ["version": 1, "wifiEnabled": wifiEnabled]
        if let safeAreaPadding { value["safeAreaPadding"] = safeAreaPadding }
        if let snapToEdges { value["snapToEdges"] = snapToEdges }
        if let appsShowLabels { value["appsShowLabels"] = appsShowLabels }
        if let appIconScale { value["appIconScale"] = appIconScale }
        if let appSpacingEnabled { value["appSpacingEnabled"] = appSpacingEnabled }
        if let appSpacingScale { value["appSpacingScale"] = appSpacingScale }
        if let clockScale { value["clockScale"] = clockScale }
        if let weatherIconScale { value["weatherIconScale"] = weatherIconScale }
        func positionObject(_ position: LayoutPosition?) -> [String: Double]? {
            position.map { ["x": $0.x, "y": $0.y] }
        }
        if let clock = positionObject(clock) { value["clock"] = clock }
        if let settings = positionObject(settings) { value["settings"] = settings }
        if let wifi = positionObject(wifi) { value["wifi"] = wifi }
        if let weather = positionObject(weather) { value["weather"] = weather }
        if let apps = positionObject(apps) { value["apps"] = apps }
        return value
    }

    mutating func resetPositions() {
        clock = nil; settings = nil; wifi = nil; weather = nil; apps = nil
    }

    mutating func setWeatherStyle(_ style: WeatherStyle) {
        weatherStyle = style
        weatherStyleExplicitlySet = true
    }

    func position(for element: LayoutElement) -> LayoutPosition? {
        switch element {
        case .clock: return clock
        case .settings: return settings
        case .wifi: return wifi
        case .weather: return weather
        case .apps: return apps
        }
    }

    mutating func setPosition(_ position: LayoutPosition?, for element: LayoutElement) {
        switch element {
        case .clock: clock = position
        case .settings: settings = position
        case .wifi: wifi = position
        case .weather: weather = position
        case .apps: apps = position
        }
    }
}

enum TVFrameStyle: String, CaseIterable, Identifiable {
    case lg, samsung, tcl, hisense
    var id: String { rawValue }
    var title: String {
        switch self {
        case .lg: return "LG"
        case .samsung: return "Samsung"
        case .tcl: return "TCL"
        case .hisense: return "Hisense"
        }
    }
}

struct TVFrameAsset: Codable, Equatable, Identifiable {
    let id: String
    let name: String
    let fileName: String
    let width: Double
    let height: Double
    let screenX: Double
    let screenY: Double
    let screenWidth: Double
    let screenHeight: Double

    var screenRect: CGRect {
        CGRect(x: screenX, y: screenY, width: screenWidth, height: screenHeight)
    }
}

enum TVFrameCatalog {
    private static let assets = load()
    static let defaultFallback = TVFrameAsset(
        id: "samsung", name: "Samsung", fileName: "samsung.png",
        width: 1546, height: 1017, screenX: 0.03234, screenY: 0.07965,
        screenWidth: 0.93532, screenHeight: 0.75811
    )

    static func asset(for style: TVFrameStyle) -> TVFrameAsset {
        return assets.first(where: { $0.id == style.rawValue })
            ?? assets.first(where: { $0.id == TVFrameStyle.samsung.rawValue })
            ?? defaultFallback
    }

    private static func load() -> [TVFrameAsset] {
        guard let url = Bundle.main.url(forResource: "catalog", withExtension: "json", subdirectory: "layout-frames"),
              let data = try? Data(contentsOf: url),
              let assets = try? JSONDecoder().decode([TVFrameAsset].self, from: data) else { return [] }
        return assets.filter { $0.screenWidth > 0 && $0.screenHeight > 0 && $0.screenX >= 0 && $0.screenY >= 0 && $0.screenX + $0.screenWidth <= 1 && $0.screenY + $0.screenHeight <= 1 }
    }
}

enum LayoutGeometry {
    static func defaultPosition(for element: LayoutElement, size: LayoutSize) -> LayoutPosition {
        switch element {
        case .clock: return LayoutPosition(x: 0.045, y: 0.035)
        case .settings: return LayoutPosition(x: 0.94, y: 0.052)
        case .wifi: return LayoutPosition(x: 0.88, y: 0.052)
        case .weather: return LayoutPosition(x: 0.98 - size.width, y: 0.125)
        case .apps: return LayoutPosition(x: 0.04, y: 0.78)
        }
    }

    static func size(for element: LayoutElement, appCount: Int, weatherStyle: WeatherStyle, daily: Bool, weatherLocationCount: Int = 1, weatherPortraitCards: Bool = false, inlineWeatherContentSize: LayoutSize? = nil, clockScale: Double = 1, weatherIconScale: Double = 1, appsShowLabels: Bool = true, appIconScale: Double = 1, appSpacingScale: Double = 1, safeAreaPadding: Double = 0.02, shortcutStyle: ShortcutStyle = .minimal) -> LayoutSize {
        switch element {
        case .clock: return LayoutSize(width: 0.28 * clockScale, height: 0.11 * clockScale)
        case .settings, .wifi: return LayoutSize(width: 0.04, height: 0.06)
        case .apps:
            let metrics = appCarouselMetrics(appCount: appCount, appIconScale: appIconScale, appSpacingScale: appSpacingScale, safeAreaPadding: safeAreaPadding, appsShowLabels: appsShowLabels, shortcutStyle: shortcutStyle)
            return LayoutSize(width: metrics.rowWidth, height: metrics.rowHeight)
        case .weather:
            if weatherStyle == .icon { return inlineWeatherSize(content: inlineWeatherContentSize, weatherIconScale: weatherIconScale) }
            if weatherPortraitCards && weatherStyle != .icon {
                let metrics = weatherPortraitMetrics(locationCount: weatherLocationCount, safeAreaPadding: safeAreaPadding)
                return LayoutSize(width: metrics.groupWidth, height: metrics.groupHeight)
            }
            let width = weatherStyle == .compact ? 0.3523 : 0.4069
            let height: Double
            if weatherStyle == .compact { height = 0.221 }
            else if weatherStyle == .scenic { height = 0.4069 }
            else { height = daily ? 0.4628 : 0.3172 }
            return LayoutSize(width: width, height: height)
        }
    }

    /// The inline widget uses its actual glyph and temperature bounds once the
    /// preview has measured them. The legacy allowance remains only until then.
    static func inlineWeatherSize(content: LayoutSize?, weatherIconScale: Double) -> LayoutSize {
        let scale = min(2, max(0.5, weatherIconScale))
        let fallback = LayoutSize(width: 0.13 * scale, height: 0.06 * scale)
        guard let content,
              content.width.isFinite, content.height.isFinite,
              content.width > 0, content.height > 0,
              content.width <= 1, content.height <= 1 else { return fallback }
        return content
    }

    struct WeatherPortraitMetrics: Equatable {
        let count: Int
        let gap: Double
        let cardWidth: Double
        let groupWidth: Double
        let groupHeight: Double
    }

    /// Portrait cards preserve a physical 2:3 aspect ratio in the 16:9
    /// normalized launcher coordinate space. The group never crosses the
    /// shared horizontal safe edge.
    static func weatherPortraitMetrics(locationCount: Int, safeAreaPadding: Double = 0.02) -> WeatherPortraitMetrics {
        let count = min(6, max(1, locationCount))
        let gap = 0.012
        let padding = min(0.10, max(0, safeAreaPadding))
        let safeWidth = 1 - 2 * padding * 9 / 16
        let groupWidth = min(Double(count) * 0.18 + Double(count - 1) * gap, safeWidth)
        let cardWidth = (groupWidth - Double(count - 1) * gap) / Double(count)
        return WeatherPortraitMetrics(
            count: count,
            gap: gap,
            cardWidth: cardWidth,
            groupWidth: groupWidth,
            groupHeight: cardWidth * 8 / 3
        )
    }

    /// Converts normalized portrait geometry to a 16:9 nominal canvas. Its
    /// height must use the canvas height, not its reference width.
    static func weatherPortraitNominalSize(locationCount: Int, safeAreaPadding: Double = 0.02, referenceWidth: Double = 1000) -> LayoutSize {
        let metrics = weatherPortraitMetrics(locationCount: locationCount, safeAreaPadding: safeAreaPadding)
        return LayoutSize(
            width: metrics.groupWidth * referenceWidth,
            height: metrics.groupHeight * referenceWidth * 9 / 16
        )
    }

    struct AppCarouselMetrics: Equatable {
        struct PageLayout: Equatable {
            /// Zero on the initial page so its first shortcut meets the safe edge.
            let leadingGutter: Double
            /// Active cards plus the page-specific leading and normal trailing gutter.
            let coreRowWidth: Double
            /// The leading edge of the one upcoming-shortcut hint, when present.
            let upcomingHintOffset: Double
        }

        let capacity: Int
        let visibleCount: Int
        let step: Double
        let gap: Double
        let tileWidth: Double
        let tileScale: Double
        let cardsWidth: Double
        let focusOverflow: Double
        let gutter: Double
        let arrowTargetWidth: Double
        /// The later-page core: cards plus the two arrow/focus gutters.
        let coreRowWidth: Double
        /// The full safe row that can reveal one faded upcoming shortcut.
        let rowWidth: Double
        let rowHeight: Double

        var isPaged: Bool { gutter > 0 }

        /// Page zero has no previous action, so it must not reserve its gutter.
        /// Later pages retain the existing back-arrow clearance.
        func pageLayout(for page: Int) -> PageLayout {
            let leadingGutter = isPaged && page > 0 ? gutter : 0
            return PageLayout(
                leadingGutter: leadingGutter,
                coreRowWidth: cardsWidth + leadingGutter + gutter,
                upcomingHintOffset: leadingGutter + Double(capacity) * step
            )
        }
    }

    /// Matches the receiver’s 16:9 launcher geometry. The base maximum keeps
    /// the historic eight-pill/five-card footprint at the default scale.
    static func appCarouselMetrics(appCount: Int, appIconScale: Double = 1, appSpacingScale: Double = 1, safeAreaPadding: Double = 0.02, appsShowLabels: Bool = true, shortcutStyle: ShortcutStyle = .minimal) -> AppCarouselMetrics {
        let scale = min(1.5, max(0.5, appIconScale))
        let spacingScale = min(3, max(0, appSpacingScale))
        let padding = min(0.10, max(0, safeAreaPadding))
        let baseMax = shortcutStyle == .minimal ? 0.86 : 0.89
        let baseTileWidth = shortcutStyle == .minimal ? 0.09 : 0.17
        let tileWidth = baseTileWidth * scale
        let gap = (shortcutStyle == .minimal ? 0.02 : 0.01) * scale * spacingScale
        let step = tileWidth + gap
        let aspect = 16.0 / 9.0
        let fullSafeWidth = 1 - 2 * padding * min(1, 1 / aspect)
        let available = min(baseMax, fullSafeWidth)
        let baseCapacity = max(1, Int(floor((available + gap) / step + 0.000_001)))
        // A focused tile can use only the actual inter-tile gap. At zero spacing,
        // reserve no expansion room so adjacent buttons still only touch.
        let focusOverflow = min(tileWidth * 0.03, gap)
        let isPaged = appCount > baseCapacity
        let arrowTargetWidth = isPaged ? 0.035 : 0
        let gutter = isPaged ? arrowTargetWidth + focusOverflow + 0.001 : 0
        let availablePaged = available - 2 * gutter
        let capacity = isPaged
            ? max(1, Int(floor((availablePaged + gap) / step + 0.000_001)))
            : baseCapacity
        let visibleCount = min(max(1, appCount), capacity)
        let cardsWidth = Double(visibleCount) * step - gap
        let coreRowWidth = cardsWidth + 2 * gutter
        let baseHeight: Double
        switch shortcutStyle {
        case .minimal: baseHeight = appsShowLabels ? 0.167 : 0.12
        case .cards: baseHeight = appsShowLabels ? 0.217 : 0.17
        }
        return AppCarouselMetrics(
            capacity: capacity,
            visibleCount: visibleCount,
            step: step,
            gap: gap,
            tileWidth: tileWidth,
            tileScale: scale,
            cardsWidth: cardsWidth,
            focusOverflow: focusOverflow,
            gutter: gutter,
            arrowTargetWidth: arrowTargetWidth,
            coreRowWidth: coreRowWidth,
            rowWidth: isPaged ? fullSafeWidth : coreRowWidth,
            rowHeight: baseHeight * scale
        )
    }

    /// Include the items currently in the upper third, regardless of their role.
    /// Clock and inline weather content are vertically centered in these bounds.
    static func topRowItems(_ layout: LayoutSettings, appCount: Int, daily: Bool, weatherLocationCount: Int = 1, weatherPortraitCards: Bool = false, inlineWeatherContentSize: LayoutSize? = nil, shortcutStyle: ShortcutStyle = .minimal) -> [(element: LayoutElement, position: LayoutPosition, size: LayoutSize)] {
        LayoutElement.allCases.compactMap { element in
            guard element != .wifi || layout.wifiEnabled,
                  element != .apps || appCount > 0 else { return nil }
            let dimensions = size(for: element, appCount: appCount, weatherStyle: layout.weatherStyle, daily: daily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, clockScale: layout.effectiveClockScale, weatherIconScale: layout.effectiveWeatherIconScale, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
            let position = resolvedPosition(layout, element: element, size: dimensions)
            guard position.y + dimensions.height / 2 <= 1.0 / 3.0 else { return nil }
            return (element, position, dimensions)
        }
    }

    /// Align centers in one operation; independent edge snapping would undo the row.
    static func alignTopRow(in layout: inout LayoutSettings, appCount: Int, daily: Bool, weatherLocationCount: Int = 1, weatherPortraitCards: Bool = false, inlineWeatherContentSize: LayoutSize? = nil, shortcutStyle: ShortcutStyle = .minimal) {
        let items = topRowItems(layout, appCount: appCount, daily: daily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, shortcutStyle: shortcutStyle)
        guard items.count > 1 else { return }
        let halfHeight = items.map { $0.size.height / 2 }.max() ?? 0
        let meanCenter = items.reduce(0.0) { $0 + $1.position.y + $1.size.height / 2 } / Double(items.count)
        let center = max(layout.effectivePadding + halfHeight, min(1 - layout.effectivePadding - halfHeight, meanCenter))
        for item in items {
            layout.setPosition(LayoutPosition(x: item.position.x, y: center - item.size.height / 2), for: item.element)
        }
    }

    /// Snap to the closest safe edge using physical distance, not viewport fractions.
    static func nearestEdge(_ position: LayoutPosition, size: LayoutSize, padding: Double, includeCorners: Bool = false) -> LayoutPosition {
        var result = constrained(position, size: size, padding: padding)
        let left = padding * 9 / 16, right = 1 - size.width - left
        let top = padding, bottom = 1 - size.height - top
        let distances = [
            abs(result.x - left) * 16 / 9,
            abs(right - result.x) * 16 / 9,
            abs(result.y - top),
            abs(bottom - result.y)
        ]
        // Near a corner, attract on both axes. Otherwise an item already on the top/bottom
        // edge has a zero-distance winner forever and can never snap horizontally again.
        let cornerReach = 0.08 // Physical distance as a fraction of the screen's shorter edge.
        let horizontal = min(distances[0], distances[1])
        let vertical = min(distances[2], distances[3])
        if includeCorners && horizontal <= cornerReach && vertical <= cornerReach {
            result.x = distances[0] <= distances[1] ? left : right
            result.y = distances[2] <= distances[3] ? top : bottom
            return constrained(result, size: size, padding: padding)
        }
        switch distances.enumerated().min(by: { $0.element < $1.element })?.offset {
        case 0: result.x = left
        case 1: result.x = right
        case 2: result.y = top
        case 3: result.y = bottom
        default: break
        }
        return constrained(result, size: size, padding: padding)
    }

    /// Prefer a corner only when doing so leaves the other controls accessible.
    static func snappedPosition(_ layout: LayoutSettings, element: LayoutElement, size dimensions: LayoutSize, appCount: Int, daily: Bool, weatherLocationCount: Int = 1, weatherPortraitCards: Bool = false, inlineWeatherContentSize: LayoutSize? = nil, shortcutStyle: ShortcutStyle = .minimal) -> LayoutPosition {
        let origin = resolvedPosition(layout, element: element, size: dimensions)
        let candidate = nearestEdge(origin, size: dimensions, padding: layout.effectivePadding, includeCorners: true)
        func collides(_ candidate: LayoutPosition) -> Bool {
            LayoutElement.allCases.contains { other in
                guard other != element, other != .wifi || layout.wifiEnabled,
                      other != .apps || appCount > 0 else { return false }
                let otherSize = size(for: other, appCount: appCount, weatherStyle: layout.weatherStyle, daily: daily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, clockScale: layout.effectiveClockScale, weatherIconScale: layout.effectiveWeatherIconScale, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
                let otherOrigin = resolvedPosition(layout, element: other, size: otherSize)
                return candidate.x < otherOrigin.x + otherSize.width - 0.0001
                    && candidate.x + dimensions.width > otherOrigin.x + 0.0001
                    && candidate.y < otherOrigin.y + otherSize.height - 0.0001
                    && candidate.y + dimensions.height > otherOrigin.y + 0.0001
            }
        }
        guard collides(candidate) else { return candidate }
        let edge = nearestEdge(origin, size: dimensions, padding: layout.effectivePadding)
        return collides(edge) ? origin : edge
    }

    static func setSnapping(_ enabled: Bool, in layout: inout LayoutSettings, appCount: Int, daily: Bool, weatherLocationCount: Int = 1, weatherPortraitCards: Bool = false, inlineWeatherContentSize: LayoutSize? = nil, shortcutStyle: ShortcutStyle = .minimal) {
        layout.snapToEdges = enabled
        guard enabled else { return }
        // Always recalculate from current positions, even if snapping was previously enabled.
        for element in LayoutElement.allCases {
            guard element != .wifi || layout.wifiEnabled, element != .apps || appCount > 0 else { continue }
            let dimensions = size(for: element, appCount: appCount, weatherStyle: layout.weatherStyle, daily: daily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, clockScale: layout.effectiveClockScale, weatherIconScale: layout.effectiveWeatherIconScale, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
            layout.setPosition(snappedPosition(layout, element: element, size: dimensions, appCount: appCount, daily: daily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, shortcutStyle: shortcutStyle), for: element)
        }
    }

    /// Preserve a bottom-aligned row when its labels add or remove height.
    static func setAppLabels(_ shown: Bool, in layout: inout LayoutSettings, appCount: Int, shortcutStyle: ShortcutStyle = .minimal) {
        let oldSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        var origin = resolvedPosition(layout, element: .apps, size: oldSize)
        let atBottom = abs(origin.y + oldSize.height - (1 - layout.effectivePadding)) < 0.000_01
        layout.appsShowLabels = shown
        let newSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: shown, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        if atBottom { origin.y = 1 - layout.effectivePadding - newSize.height }
        layout.apps = layout.effectiveSnapToEdges
            ? nearestEdge(origin, size: newSize, padding: layout.effectivePadding)
            : constrained(origin, size: newSize, padding: layout.effectivePadding)
    }

    /// Keep an app row's edge relationship when the alternate shortcut style
    /// changes its page width or height.
    static func setAppShortcutStyle(_ style: ShortcutStyle, in layout: inout LayoutSettings, appCount: Int, currentStyle: ShortcutStyle) {
        let oldSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: currentStyle)
        var origin = resolvedPosition(layout, element: .apps, size: oldSize)
        let atBottom = abs(origin.y + oldSize.height - (1 - layout.effectivePadding)) < 0.000_01
        let atRight = abs(origin.x + oldSize.width - (1 - layout.effectivePadding * 9 / 16)) < 0.000_01
        let newSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: style)
        if atBottom { origin.y = 1 - layout.effectivePadding - newSize.height }
        if atRight { origin.x = 1 - layout.effectivePadding * 9 / 16 - newSize.width }
        layout.apps = layout.effectiveSnapToEdges
            ? nearestEdge(origin, size: newSize, padding: layout.effectivePadding)
            : constrained(origin, size: newSize, padding: layout.effectivePadding)
    }

    /// Preserve safe-area edge anchors when the shared shortcut scale changes.
    static func setAppIconScale(_ value: Double, in layout: inout LayoutSettings, appCount: Int, shortcutStyle: ShortcutStyle = .minimal) {
        let oldSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        var origin = resolvedPosition(layout, element: .apps, size: oldSize)
        let insetX = layout.effectivePadding * 9 / 16
        let atBottom = abs(origin.y + oldSize.height - (1 - layout.effectivePadding)) < 0.000_01
        let atRight = abs(origin.x + oldSize.width - (1 - insetX)) < 0.000_01
        layout.appIconScale = min(1.5, max(0.5, value))
        let newSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        if atBottom { origin.y = 1 - layout.effectivePadding - newSize.height }
        if atRight { origin.x = 1 - insetX - newSize.width }
        layout.apps = layout.effectiveSnapToEdges
            ? nearestEdge(origin, size: newSize, padding: layout.effectivePadding)
            : constrained(origin, size: newSize, padding: layout.effectivePadding)
    }

    /// Scale shortcut tiles from a corner without treating the paged row's full
    /// safe-area viewport as a resizable tile. The tile and row height provide
    /// a stable proportional resize basis for both shortcut styles.
    static func appIconScaleForResize(
        from scale: Double,
        style: ShortcutStyle,
        showsLabels: Bool,
        position: LayoutPosition,
        size: LayoutSize,
        cornerX: Double,
        cornerY: Double,
        translation: LayoutPosition,
        padding: Double
    ) -> Double {
        let baseTileWidth = style == .minimal ? 0.09 : 0.17
        let baseHeight: Double
        switch style {
        case .minimal: baseHeight = showsLabels ? 0.167 : 0.12
        case .cards: baseHeight = showsLabels ? 0.217 : 0.17
        }
        let delta = (translation.x * cornerX * baseTileWidth + translation.y * cornerY * baseHeight) / (baseTileWidth * baseTileWidth + baseHeight * baseHeight)
        let verticalLimit = cornerY < 0
            ? (position.y + size.height - padding) / baseHeight
            : (1 - padding - position.y) / baseHeight
        return max(0.5, min(1.5, verticalLimit, scale + delta))
    }

    /// Keep the corner opposite a direct app-row resize in place, then apply
    /// the normal safe-area clamp if the requested size cannot fit.
    static func appRowResizePosition(
        position: LayoutPosition,
        oldSize: LayoutSize,
        newSize: LayoutSize,
        cornerX: Double,
        cornerY: Double,
        padding: Double
    ) -> LayoutPosition {
        var origin = position
        if cornerX < 0 { origin.x = position.x + oldSize.width - newSize.width }
        if cornerY < 0 { origin.y = position.y + oldSize.height - newSize.height }
        return constrained(origin, size: newSize, padding: padding)
    }

    /// Spacing changes alter the row width but never the shortcut tiles themselves.
    /// Keep right and bottom edge anchors fixed while recalculating its bounds.
    static func setAppSpacingEnabled(_ enabled: Bool, in layout: inout LayoutSettings, appCount: Int, shortcutStyle: ShortcutStyle = .minimal) {
        let oldSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        var origin = resolvedPosition(layout, element: .apps, size: oldSize)
        let insetX = layout.effectivePadding * 9 / 16
        let atBottom = abs(origin.y + oldSize.height - (1 - layout.effectivePadding)) < 0.000_01
        let atRight = abs(origin.x + oldSize.width - (1 - insetX)) < 0.000_01
        layout.appSpacingEnabled = enabled
        let newSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        if atBottom { origin.y = 1 - layout.effectivePadding - newSize.height }
        if atRight { origin.x = 1 - insetX - newSize.width }
        layout.apps = layout.effectiveSnapToEdges
            ? nearestEdge(origin, size: newSize, padding: layout.effectivePadding)
            : constrained(origin, size: newSize, padding: layout.effectivePadding)
    }

    static func setAppSpacingScale(_ value: Double, in layout: inout LayoutSettings, appCount: Int, shortcutStyle: ShortcutStyle = .minimal) {
        let oldSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        var origin = resolvedPosition(layout, element: .apps, size: oldSize)
        let insetX = layout.effectivePadding * 9 / 16
        let atBottom = abs(origin.y + oldSize.height - (1 - layout.effectivePadding)) < 0.000_01
        let atRight = abs(origin.x + oldSize.width - (1 - insetX)) < 0.000_01
        layout.appSpacingScale = value.isFinite ? min(3, max(0, value)) : 1
        let newSize = size(for: .apps, appCount: appCount, weatherStyle: layout.weatherStyle, daily: false, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: layout.effectivePadding, shortcutStyle: shortcutStyle)
        if atBottom { origin.y = 1 - layout.effectivePadding - newSize.height }
        if atRight { origin.x = 1 - insetX - newSize.width }
        layout.apps = layout.effectiveSnapToEdges
            ? nearestEdge(origin, size: newSize, padding: layout.effectivePadding)
            : constrained(origin, size: newSize, padding: layout.effectivePadding)
    }

    /// Resize proportionally from a corner, keeping its opposite corner anchored.
    static func resizedElement(base: LayoutSize = LayoutSize(width: 0.28, height: 0.11), position: LayoutPosition, scale: Double, cornerX: Double, cornerY: Double, translation: LayoutPosition, padding: Double) -> (position: LayoutPosition, scale: Double) {
        let anchorX = cornerX < 0 ? position.x + base.width * scale : position.x
        let anchorY = cornerY < 0 ? position.y + base.height * scale : position.y
        let insetX = padding * 9 / 16
        let availableX = cornerX < 0 ? anchorX - insetX : 1 - insetX - anchorX
        let availableY = cornerY < 0 ? anchorY - padding : 1 - padding - anchorY
        let maxScale = min(2, availableX / base.width, availableY / base.height)
        // Project the pointer displacement onto the corner diagonal in physical screen units.
        let baseX = base.width * 16 / 9, baseY = base.height
        let delta = (translation.x * 16 / 9 * cornerX * baseX + translation.y * cornerY * baseY) / (baseX * baseX + baseY * baseY)
        let next = max(0.5, min(maxScale, scale + delta))
        let origin = LayoutPosition(x: cornerX < 0 ? anchorX - base.width * next : anchorX, y: cornerY < 0 ? anchorY - base.height * next : anchorY)
        return (constrained(origin, size: LayoutSize(width: base.width * next, height: base.height * next), padding: padding), next)
    }

    static func resolvedPosition(_ layout: LayoutSettings, element: LayoutElement, size: LayoutSize) -> LayoutPosition {
        constrained(layout.position(for: element) ?? defaultPosition(for: element, size: size), size: size, padding: layout.effectivePadding)
    }

    /// The preview and receiver use a 16:9 viewport. Both axes inset by the same pixels.
    static func constrained(_ position: LayoutPosition, size: LayoutSize, padding: Double, snap: Bool = false) -> LayoutPosition {
        func axis(_ value: Double, size: Double, inset: Double, threshold: Double) -> Double {
            let upper = 1 - size - inset
            guard upper >= inset else { return max(0, (1 - size) / 2) }
            let bounded = min(upper, max(inset, value))
            guard snap else { return bounded }
            if abs(bounded - inset) <= threshold { return inset }
            if abs(bounded - upper) <= threshold { return upper }
            return bounded
        }
        return LayoutPosition(
            x: axis(position.x, size: size.width, inset: padding * 9 / 16, threshold: 0.015 * 9 / 16),
            y: axis(position.y, size: size.height, inset: padding, threshold: 0.015))
    }

    /// Keep relative placement, including edge anchors, when the safe area changes.
    static func setPadding(_ value: Double, in layout: inout LayoutSettings, appCount: Int, daily: Bool, weatherLocationCount: Int = 1, weatherPortraitCards: Bool = false, inlineWeatherContentSize: LayoutSize? = nil, shortcutStyle: ShortcutStyle = .minimal) {
        let old = layout.effectivePadding
        let next = min(0.10, max(0, value))
        guard abs(old - next) > 0.000_001 else { return }
        func remap(_ value: Double, size: Double, old: Double, next: Double) -> Double {
            let available = 1 - size - old * 2
            let fraction = available > 0 ? min(1, max(0, (value - old) / available)) : 0.5
            return next + fraction * max(0, 1 - size - next * 2)
        }
        for element in LayoutElement.allCases {
            let oldSize = size(for: element, appCount: appCount, weatherStyle: layout.weatherStyle, daily: daily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, clockScale: layout.effectiveClockScale, weatherIconScale: layout.effectiveWeatherIconScale, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: old, shortcutStyle: shortcutStyle)
            let position = resolvedPosition(layout, element: element, size: oldSize)
            let oldInsetX = old * 9 / 16
            let oldRight = 1 - oldSize.width - oldInsetX
            let oldBottom = 1 - oldSize.height - old
            let anchoredLeft = abs(position.x - oldInsetX) < 0.000_01
            let anchoredRight = abs(position.x - oldRight) < 0.000_01
            let anchoredTop = abs(position.y - old) < 0.000_01
            let anchoredBottom = abs(position.y - oldBottom) < 0.000_01
            let mapped = LayoutPosition(
                x: remap(position.x, size: oldSize.width, old: oldInsetX, next: next * 9 / 16),
                y: remap(position.y, size: oldSize.height, old: old, next: next))
            let newSize = size(for: element, appCount: appCount, weatherStyle: layout.weatherStyle, daily: daily, weatherLocationCount: weatherLocationCount, weatherPortraitCards: weatherPortraitCards, inlineWeatherContentSize: inlineWeatherContentSize, clockScale: layout.effectiveClockScale, weatherIconScale: layout.effectiveWeatherIconScale, appsShowLabels: layout.effectiveAppsShowLabels, appIconScale: layout.effectiveAppIconScale, appSpacingScale: layout.effectiveAppSpacingScale, safeAreaPadding: next, shortcutStyle: shortcutStyle)
            var remapped = mapped
            if anchoredLeft { remapped.x = next * 9 / 16 }
            if anchoredRight { remapped.x = 1 - newSize.width - next * 9 / 16 }
            if anchoredTop { remapped.y = next }
            if anchoredBottom { remapped.y = 1 - newSize.height - next }
            layout.setPosition(layout.effectiveSnapToEdges ? nearestEdge(remapped, size: newSize, padding: next) : constrained(remapped, size: newSize, padding: next), for: element)
        }
        layout.safeAreaPadding = next
    }

    static func selfTestsPass() -> Bool {
        let weather = size(for: .weather, appCount: 0, weatherStyle: .minimal, daily: true)
        let portraitSingle = weatherPortraitMetrics(locationCount: 1, safeAreaPadding: 0.02)
        let portraitThree = weatherPortraitMetrics(locationCount: 3, safeAreaPadding: 0.02)
        let portraitSix = weatherPortraitMetrics(locationCount: 6, safeAreaPadding: 0.02)
        let portraitSize = size(for: .weather, appCount: 0, weatherStyle: .scenic, daily: false, weatherLocationCount: 6, weatherPortraitCards: true, safeAreaPadding: 0.02)
        let compactPortraitSize = size(for: .weather, appCount: 0, weatherStyle: .compact, daily: false, weatherLocationCount: 6, weatherPortraitCards: true, safeAreaPadding: 0.02)
        let disabledPortraitSize = size(for: .weather, appCount: 0, weatherStyle: .scenic, daily: false, weatherLocationCount: 6, safeAreaPadding: 0.02)
        let iconPortraitSize = size(for: .weather, appCount: 0, weatherStyle: .icon, daily: false, weatherLocationCount: 6, weatherPortraitCards: true, safeAreaPadding: 0.02)
        let portraitThreeNominal = weatherPortraitNominalSize(locationCount: 3, safeAreaPadding: 0.02)
        let inlineShort = LayoutSize(width: 0.071, height: 0.044)
        let inlineLong = LayoutSize(width: 0.116, height: 0.044)
        let clock = size(for: .clock, appCount: 0, weatherStyle: .minimal, daily: false)
        func near(_ lhs: Double, _ rhs: Double) -> Bool { abs(lhs - rhs) < 0.000_001 }
        let weatherOrigin = defaultPosition(for: .weather, size: weather)
        let clamped = LayoutPosition(x: -4, y: 4).clamped(for: clock)
        let safe = constrained(LayoutPosition(x: -1, y: 2), size: clock, padding: 0.06)
        let snapped = constrained(LayoutPosition(x: 0.039, y: 0.065), size: clock, padding: 0.06, snap: true)
        var layout = LayoutSettings()
        layout.safeAreaPadding = 0.02
        layout.clock = LayoutPosition(x: 0.02 * 9 / 16, y: 0.02)
        setPadding(0.06, in: &layout, appCount: 8, daily: true)
        let persisted = (try? JSONEncoder().encode(layout)).flatMap { try? JSONDecoder().decode(LayoutSettings.self, from: $0) }
        let edge = nearestEdge(LayoutPosition(x: 0.4, y: 0.13), size: clock, padding: 0.02)
        var repeatedSnap = LayoutSettings()
        repeatedSnap.safeAreaPadding = 0.045
        let inset = repeatedSnap.effectivePadding * 9 / 16
        repeatedSnap.clock = LayoutPosition(x: inset + 0.035, y: 0.045)
        setSnapping(true, in: &repeatedSnap, appCount: 8, daily: true)
        let firstCorner = repeatedSnap.clock
        setSnapping(false, in: &repeatedSnap, appCount: 8, daily: true)
        repeatedSnap.clock = LayoutPosition(x: inset + 0.025, y: 1 - 0.045 - clock.height - 0.035)
        repeatedSnap.apps = LayoutPosition(x: inset, y: 0.4)
        let freePosition = repeatedSnap.clock
        setSnapping(false, in: &repeatedSnap, appCount: 8, daily: true)
        let offPreservesPosition = repeatedSnap.clock == freePosition
        setSnapping(true, in: &repeatedSnap, appCount: 8, daily: true)
        let secondCorner = repeatedSnap.clock
        repeatedSnap.clock = LayoutPosition(x: inset + 0.03, y: 0.045)
        setSnapping(true, in: &repeatedSnap, appCount: 8, daily: true)
        let resnapsCurrentPosition = repeatedSnap.clock == firstCorner
        let dragToBottomCorner = nearestEdge(
            LayoutPosition(x: inset + 0.025, y: 1 - 0.045 - clock.height),
            size: clock, padding: 0.045, includeCorners: true)
        let grown = resizedElement(position: LayoutPosition(x: 0.2, y: 0.3), scale: 1, cornerX: 1, cornerY: 1, translation: LayoutPosition(x: 0.14, y: 0.055), padding: 0.02)
        let grownFromTop = resizedElement(position: LayoutPosition(x: 0.2, y: 0.3), scale: 1, cornerX: -1, cornerY: -1, translation: LayoutPosition(x: -0.14, y: -0.055), padding: 0.02)
        let bounded = resizedElement(position: LayoutPosition(x: 0.65, y: 0.8), scale: 1, cornerX: 1, cornerY: 1, translation: LayoutPosition(x: 9, y: 9), padding: 0.02)
        var options = layout
        let inlineSize = LayoutSize(width: 0.13, height: 0.06)
        let inlineGrowth = resizedElement(base: inlineSize, position: LayoutPosition(x: 0.5, y: 0.2), scale: 1, cornerX: -1, cornerY: -1, translation: LayoutPosition(x: -0.065, y: -0.03), padding: 0.02)
        options.weatherIconScale = 1.5
        options.clockScale = 1.5
        setSnapping(true, in: &options, appCount: 8, daily: true)
        let optionsRoundTrip = (try? JSONEncoder().encode(options)).flatMap { try? JSONDecoder().decode(LayoutSettings.self, from: $0) }
        let inlineShortSize = size(for: .weather, appCount: 0, weatherStyle: .icon, daily: false, inlineWeatherContentSize: inlineShort, weatherIconScale: 1)
        let inlineLongSize = size(for: .weather, appCount: 0, weatherStyle: .icon, daily: false, inlineWeatherContentSize: inlineLong, weatherIconScale: 1)
        var noWiFi = LayoutSettings()
        noWiFi.wifiEnabled = false
        noWiFi.weatherStyle = .icon
        noWiFi.weather = LayoutPosition(x: 0.83, y: 0.052)
        noWiFi.settings = LayoutPosition(x: 0.94, y: 0.052)
        let noWiFiWeather = resolvedPosition(noWiFi, element: .weather, size: inlineShortSize)
        let noWiFiConstrained = constrained(LayoutPosition(x: 0.91, y: 0.052), size: inlineShortSize, padding: noWiFi.effectivePadding)
        var hiddenWiFiSnapLayout = noWiFi
        hiddenWiFiSnapLayout.settings = LayoutPosition(x: 0.94, y: 0.2)
        hiddenWiFiSnapLayout.weather = LayoutPosition(x: 0.88, y: 0.052)
        let hiddenWiFiSnap = snappedPosition(hiddenWiFiSnapLayout, element: .weather, size: inlineShortSize, appCount: 0, daily: false, inlineWeatherContentSize: inlineShort)
        var withWiFi = hiddenWiFiSnapLayout
        withWiFi.wifiEnabled = true
        withWiFi.wifi = LayoutPosition(x: 0.88, y: 0.052)
        let withWiFiSnap = snappedPosition(withWiFi, element: .weather, size: inlineShortSize, appCount: 0, daily: false, inlineWeatherContentSize: inlineShort)
        // Chrome keeps its Android-compatible logical frame for wire positions
        // and safe-area clamping; editor hit-target expansion is view-local.
        let chromeSize = size(for: .settings, appCount: 0, weatherStyle: .minimal, daily: false)
        let chromeAtSafeEdge = constrained(LayoutPosition(x: 1, y: 1), size: chromeSize, padding: 0.06)
        var measuredTopRow = LayoutSettings()
        measuredTopRow.weatherStyle = .icon
        measuredTopRow.wifiEnabled = false
        measuredTopRow.clock = LayoutPosition(x: 0.05, y: 0.03)
        measuredTopRow.settings = LayoutPosition(x: 0.90, y: 0.09)
        measuredTopRow.weather = LayoutPosition(x: 0.76, y: 0.13)
        alignTopRow(in: &measuredTopRow, appCount: 0, daily: false, inlineWeatherContentSize: inlineShort)
        let measuredTopCenters = topRowItems(measuredTopRow, appCount: 0, daily: false, inlineWeatherContentSize: inlineShort)
            .map { $0.position.y + $0.size.height / 2 }
        let measuredTopRowAligned = measuredTopCenters.count == 3
            && measuredTopCenters.allSatisfy { near($0, measuredTopCenters[0]) }
        var labels = LayoutSettings()
        labels.apps = LayoutPosition(x: 0.04, y: 1 - labels.effectivePadding - 0.167)
        setAppLabels(false, in: &labels, appCount: 8)
        let hiddenBottom = labels.apps!.y + 0.12
        let labelsRoundTrip = (try? JSONEncoder().encode(labels)).flatMap { try? JSONDecoder().decode(LayoutSettings.self, from: $0) }
        setAppLabels(true, in: &labels, appCount: 8)
        let minimalPage = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, shortcutStyle: .minimal)
        let cardPage = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, shortcutStyle: .cards)
        let minimalPartialPage = size(for: .apps, appCount: 9, weatherStyle: .minimal, daily: false, appsShowLabels: true, shortcutStyle: .minimal)
        let cardPartialPage = size(for: .apps, appCount: 6, weatherStyle: .minimal, daily: false, appsShowLabels: true, shortcutStyle: .cards)
        let cardHiddenLabels = size(for: .apps, appCount: 5, weatherStyle: .minimal, daily: false, appsShowLabels: false, shortcutStyle: .cards)
        let unpagedMinimal = appCarouselMetrics(appCount: 8, appIconScale: 1, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .minimal)
        let pagedMinimal = appCarouselMetrics(appCount: 22, appIconScale: 1, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .minimal)
        let lastPageMinimal = appCarouselMetrics(appCount: 15, appIconScale: 1, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .minimal)
        let scaledMinimal = appCarouselMetrics(appCount: 22, appIconScale: 1.5, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .minimal)
        let compactMinimal = appCarouselMetrics(appCount: 22, appIconScale: 0.5, safeAreaPadding: 0.02, appsShowLabels: false, shortcutStyle: .minimal)
        let touchingMinimal = appCarouselMetrics(appCount: 22, appIconScale: 1, appSpacingScale: 0, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .minimal)
        let wideMinimal = appCarouselMetrics(appCount: 22, appIconScale: 1, appSpacingScale: 3, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .minimal)
        let touchingCards = appCarouselMetrics(appCount: 22, appIconScale: 1, appSpacingScale: 0, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .cards)
        let wideCards = appCarouselMetrics(appCount: 22, appIconScale: 1, appSpacingScale: 3, safeAreaPadding: 0.02, appsShowLabels: true, shortcutStyle: .cards)
        let firstMinimalPage = pagedMinimal.pageLayout(for: 0)
        let laterMinimalPage = pagedMinimal.pageLayout(for: 1)
        let firstTouchingPage = touchingMinimal.pageLayout(for: 0)
        let laterTouchingPage = touchingMinimal.pageLayout(for: 1)
        let firstWideCardsPage = wideCards.pageLayout(for: 0)
        let laterWideCardsPage = wideCards.pageLayout(for: 1)
        var cardLabels = LayoutSettings()
        cardLabels.apps = LayoutPosition(x: 0.04, y: 1 - cardLabels.effectivePadding - 0.217)
        setAppLabels(false, in: &cardLabels, appCount: 6, shortcutStyle: .cards)
        let hiddenCardsBottom = cardLabels.apps!.y + 0.17
        setAppLabels(true, in: &cardLabels, appCount: 6, shortcutStyle: .cards)
        var rightAlignedCards = LayoutSettings()
        rightAlignedCards.apps = LayoutPosition(x: 1 - rightAlignedCards.effectivePadding * 9 / 16 - minimalPage.width, y: 1 - rightAlignedCards.effectivePadding - 0.167)
        setAppShortcutStyle(.cards, in: &rightAlignedCards, appCount: 22, currentStyle: .minimal)
        var scaledRow = LayoutSettings()
        scaledRow.apps = LayoutPosition(x: 1 - scaledRow.effectivePadding * 9 / 16 - minimalPage.width, y: 1 - scaledRow.effectivePadding - 0.167)
        setAppIconScale(1.5, in: &scaledRow, appCount: 22)
        let scaledRowSize = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, appIconScale: scaledRow.effectiveAppIconScale, appSpacingScale: scaledRow.effectiveAppSpacingScale, safeAreaPadding: scaledRow.effectivePadding)
        let scaleRoundTrip = (try? JSONEncoder().encode(scaledRow)).flatMap { try? JSONDecoder().decode(LayoutSettings.self, from: $0) }
        let resizedCardsStartSize = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, safeAreaPadding: 0.02, shortcutStyle: .cards)
        let resizedCardsStart = LayoutPosition(x: 0.02 * 9 / 16, y: 1 - 0.02 - resizedCardsStartSize.height)
        let resizedCardsScale = appIconScaleForResize(
            from: 1,
            style: .cards,
            showsLabels: true,
            position: resizedCardsStart,
            size: resizedCardsStartSize,
            cornerX: -1,
            cornerY: -1,
            translation: LayoutPosition(x: -0.085, y: -0.1085),
            padding: 0.02)
        var directResizedCards = LayoutSettings()
        directResizedCards.apps = resizedCardsStart
        setAppIconScale(resizedCardsScale, in: &directResizedCards, appCount: 22, shortcutStyle: .cards)
        let resizedCardsSize = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, appIconScale: directResizedCards.effectiveAppIconScale, appSpacingScale: directResizedCards.effectiveAppSpacingScale, safeAreaPadding: directResizedCards.effectivePadding, shortcutStyle: .cards)
        directResizedCards.apps = appRowResizePosition(position: resizedCardsStart, oldSize: resizedCardsStartSize, newSize: resizedCardsSize, cornerX: -1, cornerY: -1, padding: directResizedCards.effectivePadding)
        let resizedMinimalStartSize = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, safeAreaPadding: 0.02, shortcutStyle: .minimal)
        let resizedMinimalStart = LayoutPosition(x: 0.02 * 9 / 16, y: 0.3)
        let resizedMinimalScale = appIconScaleForResize(
            from: 1,
            style: .minimal,
            showsLabels: true,
            position: resizedMinimalStart,
            size: resizedMinimalStartSize,
            cornerX: 1,
            cornerY: 1,
            translation: LayoutPosition(x: 0.045, y: 0.0835),
            padding: 0.02)
        var directResizedMinimal = LayoutSettings()
        directResizedMinimal.apps = resizedMinimalStart
        setAppIconScale(resizedMinimalScale, in: &directResizedMinimal, appCount: 22, shortcutStyle: .minimal)
        let resizedMinimalSize = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, appIconScale: directResizedMinimal.effectiveAppIconScale, appSpacingScale: directResizedMinimal.effectiveAppSpacingScale, safeAreaPadding: directResizedMinimal.effectivePadding, shortcutStyle: .minimal)
        directResizedMinimal.apps = appRowResizePosition(position: resizedMinimalStart, oldSize: resizedMinimalStartSize, newSize: resizedMinimalSize, cornerX: 1, cornerY: 1, padding: directResizedMinimal.effectivePadding)
        let directResizedCardsMetrics = appCarouselMetrics(appCount: 22, appIconScale: directResizedCards.effectiveAppIconScale, safeAreaPadding: directResizedCards.effectivePadding, appsShowLabels: true, shortcutStyle: .cards)
        let directResizedMinimalMetrics = appCarouselMetrics(appCount: 22, appIconScale: directResizedMinimal.effectiveAppIconScale, safeAreaPadding: directResizedMinimal.effectivePadding, appsShowLabels: true, shortcutStyle: .minimal)
        var spacing = LayoutSettings()
        spacing.apps = LayoutPosition(x: 1 - spacing.effectivePadding * 9 / 16 - minimalPage.width, y: 1 - spacing.effectivePadding - minimalPage.height)
        setAppSpacingEnabled(true, in: &spacing, appCount: 22)
        setAppSpacingScale(0, in: &spacing, appCount: 22)
        let touchingSize = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, appIconScale: spacing.effectiveAppIconScale, appSpacingScale: spacing.effectiveAppSpacingScale, safeAreaPadding: spacing.effectivePadding)
        let touchingRightAnchored = near(spacing.apps!.x + touchingSize.width, 1 - spacing.effectivePadding * 9 / 16)
        let touchingBottomAnchored = near(spacing.apps!.y + touchingSize.height, 1 - spacing.effectivePadding)
        setAppSpacingScale(3, in: &spacing, appCount: 22)
        let wideSize = size(for: .apps, appCount: 22, weatherStyle: .minimal, daily: false, appsShowLabels: true, appIconScale: spacing.effectiveAppIconScale, appSpacingScale: spacing.effectiveAppSpacingScale, safeAreaPadding: spacing.effectivePadding)
        let wideRightAnchored = near(spacing.apps!.x + wideSize.width, 1 - spacing.effectivePadding * 9 / 16)
        let wideBottomAnchored = near(spacing.apps!.y + wideSize.height, 1 - spacing.effectivePadding)
        setAppSpacingEnabled(false, in: &spacing, appCount: 22)
        let disabledSpacingRoundTrip = (try? JSONEncoder().encode(spacing)).flatMap { try? JSONDecoder().decode(LayoutSettings.self, from: $0) }
        let explicitStyle = LayoutSettings(shortcutStyle: .cards, shortcutStyleExplicitlySet: true)
        let implicitStyle = LayoutSettings()
        let explicitStyleRoundTrip = (try? JSONEncoder().encode(explicitStyle)).flatMap { try? JSONDecoder().decode(LayoutSettings.self, from: $0) }
        var aligned = LayoutSettings()
        aligned.weatherStyle = .icon
        aligned.clockScale = 2
        aligned.weatherIconScale = 0.5
        aligned.snapToEdges = true
        aligned.safeAreaPadding = 0.10
        aligned.clock = LayoutPosition(x: 0.1, y: 0.10)
        aligned.weather = LayoutPosition(x: 0.7, y: 0.11)
        aligned.settings = LayoutPosition(x: 0.9, y: 0.12)
        aligned.wifiEnabled = false
        let originalApps = aligned.apps, originalWifi = aligned.wifi
        alignTopRow(in: &aligned, appCount: 22, daily: true)
        let row = topRowItems(aligned, appCount: 22, daily: true)
        let center = row[0].position.y + row[0].size.height / 2
        let once = aligned
        alignTopRow(in: &aligned, appCount: 22, daily: true)
        var oneItem = aligned
        oneItem.clock = LayoutPosition(x: 0.1, y: 0.5)
        oneItem.weather = LayoutPosition(x: 0.7, y: 0.5)
        let beforeOneItem = oneItem
        alignTopRow(in: &oneItem, appCount: 0, daily: true)
        return row.count == 3
            && firstCorner == LayoutPosition(x: inset, y: 0.045)
            && near(secondCorner!.x, inset) && near(secondCorner!.y, 1 - 0.045 - clock.height)
            && offPreservesPosition && resnapsCurrentPosition
            && near(dragToBottomCorner.x, inset) && near(dragToBottomCorner.y, 1 - 0.045 - clock.height)
            && row.allSatisfy { near($0.position.y + $0.size.height / 2, center) && $0.position.y >= 0.1 - 0.000_001 }
            && near(aligned.clock!.x, 0.1) && near(aligned.weather!.x, 0.7) && near(aligned.settings!.x, 0.9)
            && aligned.apps == originalApps && aligned.wifi == originalWifi
            && aligned == once && oneItem == beforeOneItem
            && near(hiddenBottom, 0.98) && near(labels.apps!.y + 0.167, 0.98)
            && labelsRoundTrip?.effectiveAppsShowLabels == false
            && LayoutSettings().effectiveAppsShowLabels
            && near(weather.width, 0.4069) && near(weather.height, 0.4628)
            && portraitSingle.count == 1 && near(portraitSingle.cardWidth, 0.18) && near(portraitSingle.groupHeight, 0.48)
            && portraitThree.count == 3 && near(portraitThree.groupWidth, 0.564) && near(portraitThree.cardWidth, 0.18) && near(portraitThree.groupHeight, 0.48)
            && near(portraitThreeNominal.width, 564) && near(portraitThreeNominal.height, 270)
            && near(portraitThreeNominal.height / (portraitThreeNominal.width - 24) * 3, 1.5)
            && portraitSix.count == 6 && near(portraitSix.groupWidth, 0.9775) && near(portraitSix.cardWidth, 0.15291666666666667) && near(portraitSix.groupHeight, 0.4077777777777778)
            && portraitSize == LayoutSize(width: portraitSix.groupWidth, height: portraitSix.groupHeight)
            && compactPortraitSize == portraitSize
            && disabledPortraitSize == LayoutSize(width: 0.4069, height: 0.4069)
            && iconPortraitSize == LayoutSize(width: 0.13, height: 0.06)
            && near(weatherOrigin.x, 0.5731) && near(weatherOrigin.y, 0.125)
            && near(clamped.x, 0.01) && near(clamped.y, 0.88)
            && near(safe.x * 1920, 0.06 * 1080) && near((1 - safe.y - clock.height) * 1080, 0.06 * 1080)
            && near(snapped.x, 0.06 * 9 / 16) && near(snapped.y, 0.06)
            && near(layout.clock!.x, 0.06 * 9 / 16) && near(layout.clock!.y, 0.06)
            && persisted == layout
            && near(edge.x, 0.4) && near(edge.y, 0.02)
            && near(grown.scale, 1.5) && grown.position == LayoutPosition(x: 0.2, y: 0.3)
            && near(grownFromTop.position.x + 0.28 * grownFromTop.scale, 0.48)
            && near(grownFromTop.position.y + 0.11 * grownFromTop.scale, 0.41)
            && bounded.position.x + 0.28 * bounded.scale <= 1 - 0.02 * 9 / 16 + 0.000_001
            && near(inlineGrowth.scale, 1.5)
            && near(inlineGrowth.position.x + inlineSize.width * inlineGrowth.scale, 0.63)
            && near(inlineGrowth.position.y + inlineSize.height * inlineGrowth.scale, 0.26)
            && near(size(for: .weather, appCount: 0, weatherStyle: .icon, daily: false, weatherIconScale: 2).width, 0.26)
            && inlineShortSize == inlineShort && inlineLongSize == inlineLong && inlineShortSize.width < inlineLongSize.width
            && near(noWiFiWeather.x, 0.83) && near(noWiFiConstrained.x, 0.91) && noWiFiConstrained.x > 0.88
            && near(hiddenWiFiSnap.x, 1 - inlineShort.width - noWiFi.effectivePadding * 9 / 16)
            && withWiFiSnap == LayoutPosition(x: 0.88, y: 0.052)
            && chromeSize == LayoutSize(width: 0.04, height: 0.06)
            && near(chromeAtSafeEdge.x + chromeSize.width, 1 - 0.06 * 9 / 16)
            && near(chromeAtSafeEdge.y + chromeSize.height, 1 - 0.06)
            && measuredTopRowAligned
            && near(size(for: .weather, appCount: 0, weatherStyle: .compact, daily: false, weatherIconScale: 2).width, 0.3523)
            && near(minimalPage.width, 0.9775) && near(minimalPage.height, 0.167)
            && near(cardPage.width, 0.9775) && near(cardPage.height, 0.217)
            && minimalPartialPage == minimalPage && cardPartialPage == cardPage
            && near(cardHiddenLabels.height, 0.17)
            && !unpagedMinimal.isPaged && near(unpagedMinimal.gutter, 0) && near(unpagedMinimal.coreRowWidth, 0.86) && near(unpagedMinimal.rowWidth, 0.86)
            && pagedMinimal.capacity == 7 && near(pagedMinimal.coreRowWidth, 0.8274) && near(pagedMinimal.rowWidth, 0.9775)
            && lastPageMinimal.capacity == pagedMinimal.capacity && lastPageMinimal.visibleCount == pagedMinimal.visibleCount && near(lastPageMinimal.coreRowWidth, pagedMinimal.coreRowWidth) && near(lastPageMinimal.rowWidth, pagedMinimal.rowWidth)
            && near(firstMinimalPage.leadingGutter, 0) && near(firstMinimalPage.coreRowWidth, 0.7887) && near(firstMinimalPage.upcomingHintOffset, 0.77)
            && near(laterMinimalPage.leadingGutter, pagedMinimal.gutter) && near(laterMinimalPage.coreRowWidth, pagedMinimal.coreRowWidth) && near(laterMinimalPage.upcomingHintOffset, 0.8087)
            && scaledMinimal.capacity == 4 && near(scaledMinimal.cardsWidth, 0.63) && near(scaledMinimal.gutter, 0.04005) && near(scaledMinimal.coreRowWidth, 0.7101) && near(scaledMinimal.rowWidth, 0.9775) && near(scaledMinimal.rowHeight, 0.2505)
            && compactMinimal.capacity == 14 && near(compactMinimal.coreRowWidth, 0.8347) && near(compactMinimal.rowWidth, 0.9775) && near(compactMinimal.rowHeight, 0.06)
            && LayoutSettings().effectiveAppSpacingScale == 1
            && touchingMinimal.capacity == 8 && near(touchingMinimal.gap, 0) && near(touchingMinimal.tileWidth, unpagedMinimal.tileWidth) && near(touchingMinimal.focusOverflow, 0) && near(touchingMinimal.gutter, 0.036) && near(touchingMinimal.coreRowWidth, 0.792) && near(touchingMinimal.rowWidth, 0.9775) && near(touchingMinimal.rowHeight, unpagedMinimal.rowHeight)
            && near(firstTouchingPage.leadingGutter, 0) && near(firstTouchingPage.upcomingHintOffset, 0.72) && near(laterTouchingPage.leadingGutter, 0.036) && near(laterTouchingPage.upcomingHintOffset, 0.756)
            && wideMinimal.capacity == 5 && near(wideMinimal.gap, unpagedMinimal.gap * 3) && near(wideMinimal.tileWidth, unpagedMinimal.tileWidth) && near(wideMinimal.focusOverflow, 0.0027) && near(wideMinimal.gutter, 0.0387) && near(wideMinimal.coreRowWidth, 0.7674) && near(wideMinimal.rowWidth, 0.9775) && near(wideMinimal.rowHeight, unpagedMinimal.rowHeight)
            && touchingCards.capacity == 4 && near(touchingCards.gap, 0) && near(touchingCards.focusOverflow, 0) && near(touchingCards.coreRowWidth, 0.752) && near(touchingCards.rowWidth, 0.9775)
            && wideCards.capacity == 4 && near(wideCards.gap, 0.03) && near(wideCards.focusOverflow, 0.0051) && near(wideCards.coreRowWidth, 0.8522) && near(wideCards.rowWidth, 0.9775)
            && near(firstWideCardsPage.leadingGutter, 0) && near(firstWideCardsPage.coreRowWidth, 0.8111) && near(firstWideCardsPage.upcomingHintOffset, 0.8)
            && near(laterWideCardsPage.leadingGutter, wideCards.gutter) && near(laterWideCardsPage.coreRowWidth, wideCards.coreRowWidth) && near(laterWideCardsPage.upcomingHintOffset, 0.8411)
            && touchingRightAnchored && touchingBottomAnchored && wideRightAnchored && wideBottomAnchored
            && spacing.appSpacingEnabled == false && spacing.appSpacingScale == 3 && spacing.effectiveAppSpacingScale == 1
            && disabledSpacingRoundTrip?.appSpacingEnabled == false && disabledSpacingRoundTrip?.appSpacingScale == 3 && disabledSpacingRoundTrip?.effectiveAppSpacingScale == 1
            && near(hiddenCardsBottom, 0.98) && near(cardLabels.apps!.y + 0.217, 0.98)
            && near(rightAlignedCards.apps!.x + cardPage.width, 1 - rightAlignedCards.effectivePadding * 9 / 16)
            && near(rightAlignedCards.apps!.y + cardPage.height, 1 - rightAlignedCards.effectivePadding)
            && near(scaledRow.apps!.x + scaledRowSize.width, 1 - scaledRow.effectivePadding * 9 / 16)
            && near(scaledRow.apps!.y + scaledRowSize.height, 1 - scaledRow.effectivePadding)
            && scaleRoundTrip?.effectiveAppIconScale == 1.5
            && near(resizedCardsScale, 1.5) && directResizedCards.effectiveAppIconScale == 1.5
            && near(directResizedCards.apps!.x + resizedCardsSize.width, 1 - directResizedCards.effectivePadding * 9 / 16)
            && near(directResizedCards.apps!.y + resizedCardsSize.height, 1 - directResizedCards.effectivePadding)
            && directResizedCardsMetrics.capacity < wideCards.capacity
            && near(resizedMinimalScale, 1.5) && directResizedMinimal.effectiveAppIconScale == 1.5
            && near(directResizedMinimal.apps!.x, resizedMinimalStart.x) && near(directResizedMinimal.apps!.y, resizedMinimalStart.y)
            && directResizedMinimalMetrics.capacity < pagedMinimal.capacity
            && explicitStyleRoundTrip == explicitStyle
            && implicitStyle.effectiveShortcutStyle(fallback: .cards) == .cards
            && explicitStyle.effectiveShortcutStyle(fallback: .minimal) == .cards
            && optionsRoundTrip == options
            && TVFrameCatalog.defaultFallback.screenRect.width == 0.93532
    }
}
