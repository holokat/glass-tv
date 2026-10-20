import Foundation

enum WeatherStyle: String, Codable, CaseIterable, Identifiable {
    case minimal, compact, scenic, icon
    var id: String { rawValue }
    var title: String { self == .icon ? "Icon & temperature" : rawValue.capitalized }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(String.self)
        // `detailed` was the former five-day forecast presentation.
        if value == "detailed" {
            self = .minimal
        } else if value == "portrait" {
            // WeatherSettings records the accompanying portraitCards migration.
            self = .scenic
        } else if let style = WeatherStyle(rawValue: value) {
            self = style
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unknown weather style: \(value)")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

enum WeatherUnit: String, Codable, CaseIterable, Identifiable {
    case celsius, fahrenheit
    var id: String { rawValue }
    var title: String { self == .celsius ? "Celsius" : "Fahrenheit" }
}

/// Shared fitting values for the fixed launcher cards. The macOS preview uses
/// these only after `ViewThatFits` rejects the full-size presentation.
enum WeatherContentSizing {
    static func compactFallbackTemperatureSize(scale: Double) -> Double {
        min(64 * scale, 53)
    }

    static func minimalFallbackTemperatureSize(scale: Double) -> Double {
        min(76 * scale, 66)
    }

    static func selfTestsPass() -> Bool {
        [1.0, 1.25, 1.6].allSatisfy { scale in
            let compactTitle = 26 * scale * 1.18
            let compactGlyph = min(56 * scale, 72)
            let compactTemperature = compactFallbackTemperatureSize(scale: scale) * 1.19
            let compactCondition = min(15 * scale, 24) * 1.2
            let compactHeight = compactTitle + 4 + max(compactGlyph, compactTemperature + 2 + compactCondition)

            let minimalTitle = 26 * scale * 1.18
            let minimalGlyph = min(72 * scale, 96)
            let minimalTemperature = minimalFallbackTemperatureSize(scale: scale) * 1.19
            let minimalCondition = min(18 * scale, 26) * 1.2
            let minimalRange = min(16 * scale, 22) * 1.2
            let minimalHeight = minimalTitle + 8 + max(minimalGlyph, minimalTemperature + 2 + minimalCondition + 2 + minimalRange)
            let dailyForecastHeight = 13 * min(scale, 1.25) * 1.2 + 5 * min(scale, 1.25) + 25 * min(scale, 1.25) + 5 * min(scale, 1.25) + 13 * min(scale, 1.25) * 1.2

            let scenicHeight = 26 * scale * 1.18 + 76 * scale * 1.19 + max(30 * scale, 14 * scale * 1.2 + 14 * scale * 1.2)
            let portraitHeight = 16 * scale * 1.2 + max(30 * scale, 34 * scale * 1.19) + max(20 * scale, 11 * scale * 1.2) + 10 * scale * 1.2 + 51

            return compactHeight <= 152
                && minimalHeight <= 232
                && minimalHeight + 1 + dailyForecastHeight <= 352
                && scenicHeight <= 306
                && portraitHeight <= 225
        }
    }
}

enum WeatherCycle: Int, CaseIterable, Identifiable {
    case off = 0, seconds30 = 30, minute1 = 60, minutes5 = 300
    var id: Int { rawValue }

    var title: String {
        switch self {
        case .off: "Off"
        case .seconds30: "30 seconds"
        case .minute1: "1 minute"
        case .minutes5: "5 minutes"
        }
    }
}

struct WeatherLocation: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var country: String
    var latitude: Double
    var longitude: Double
    var enabled: Bool
    /// The locally selected scenic artwork slot. There are at most five selectable
    /// slots even when a destination has a larger bundled image collection.
    var artworkVariant: Int

    init(
        id: String,
        name: String,
        country: String,
        latitude: Double,
        longitude: Double,
        enabled: Bool,
        artworkVariant: Int = 0
    ) {
        self.id = id
        self.name = name
        self.country = country
        self.latitude = latitude
        self.longitude = longitude
        self.enabled = enabled
        self.artworkVariant = WeatherLocation.normalizedArtworkVariant(artworkVariant)
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, country, latitude, longitude, enabled, artworkVariant
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        country = try values.decode(String.self, forKey: .country)
        latitude = try values.decode(Double.self, forKey: .latitude)
        longitude = try values.decode(Double.self, forKey: .longitude)
        enabled = try values.decode(Bool.self, forKey: .enabled)
        artworkVariant = WeatherLocation.normalizedArtworkVariant(
            try values.decodeIfPresent(Int.self, forKey: .artworkVariant) ?? 0
        )
    }

    mutating func normalizeArtworkVariant() {
        artworkVariant = WeatherLocation.normalizedArtworkVariant(artworkVariant)
    }

    static func normalizedArtworkVariant(_ variant: Int) -> Int {
        min(max(variant, 0), 4)
    }
}

struct WeatherSettings: Codable, Hashable {
    var enabled: Bool = false
    var locations: [WeatherLocation] = []
    var selectedLocationId: String?
    var style: WeatherStyle = .minimal
    var unit: WeatherUnit = .celsius
    var showTemperature: Bool = true
    var showHourly: Bool = true
    var showDaily: Bool = true
    /// Tall cards retain the selected base style, except for the inline icon style.
    var portraitCards: Bool = false
    /// Portrait cards can present every enabled saved city without changing the selected city.
    var showAllLocations: Bool = false
    /// Card typography and condition-symbol scale. Icon & temperature keeps its
    /// separate layout scale and ignores this value.
    var contentScale: Double = 1.25
    var cycleSeconds: Int = 0

    private enum CodingKeys: String, CodingKey {
        case enabled, locations, selectedLocationId, style, unit
        case showTemperature, showHourly, showDaily, portraitCards, showAllLocations, contentScale, cycleSeconds
    }

    init() { }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try values.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        locations = try values.decodeIfPresent([WeatherLocation].self, forKey: .locations) ?? []
        selectedLocationId = try values.decodeIfPresent(String.self, forKey: .selectedLocationId)
        let rawStyle = try values.decodeIfPresent(String.self, forKey: .style)
        style = try values.decodeIfPresent(WeatherStyle.self, forKey: .style) ?? .minimal
        unit = try values.decodeIfPresent(WeatherUnit.self, forKey: .unit) ?? .celsius
        showTemperature = try values.decodeIfPresent(Bool.self, forKey: .showTemperature) ?? true
        showHourly = try values.decodeIfPresent(Bool.self, forKey: .showHourly) ?? true
        showDaily = try values.decodeIfPresent(Bool.self, forKey: .showDaily) ?? true
        portraitCards = try values.decodeIfPresent(Bool.self, forKey: .portraitCards) ?? (rawStyle == "portrait")
        showAllLocations = try values.decodeIfPresent(Bool.self, forKey: .showAllLocations) ?? false
        contentScale = try values.decodeIfPresent(Double.self, forKey: .contentScale) ?? 1.25
        cycleSeconds = try values.decodeIfPresent(Int.self, forKey: .cycleSeconds) ?? 0
        normalize()
    }

    var usesPortraitCards: Bool { portraitCards && style != .icon }
    var effectiveContentScale: Double { WeatherSettings.normalizedContentScale(contentScale) }

    /// The TV uses this ordered set for the active widget. Portrait cards may show
    /// several cities, and they always exclude disabled locations.
    var widgetLocations: [WeatherLocation] {
        let enabledLocations = locations.filter(\.enabled)
        if usesPortraitCards, showAllLocations { return enabledLocations }
        if let selectedLocationId,
           let selected = enabledLocations.first(where: { $0.id == selectedLocationId }) {
            return [selected]
        }
        return enabledLocations.first.map { [$0] } ?? []
    }

    static func selfTestsPass() -> Bool {
        let legacy = try? JSONDecoder().decode(WeatherSettings.self, from: Data("{\"enabled\":true,\"style\":\"scenic\"}".utf8))
        let legacyPortrait = try? JSONDecoder().decode(WeatherSettings.self, from: Data("{\"enabled\":true,\"style\":\"portrait\"}".utf8))
        var settings = WeatherSettings()
        settings.style = .compact
        settings.portraitCards = true
        settings.showAllLocations = true
        settings.contentScale = 1.6
        settings.locations = [
            WeatherLocation(id: "tokyo", name: "Tokyo", country: "Japan", latitude: 35.6762, longitude: 139.6503, enabled: true),
            WeatherLocation(id: "london", name: "London", country: "United Kingdom", latitude: 51.5072, longitude: -0.1276, enabled: false),
            WeatherLocation(id: "new-york", name: "New York", country: "United States", latitude: 40.7128, longitude: -74.0060, enabled: true)
        ]
        settings.selectedLocationId = "new-york"
        let encoded = (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(settings))) as? [String: Any]
        let allLocations = settings.widgetLocations.map(\.id)
        let roundTrip = (try? JSONEncoder().encode(settings)).flatMap { try? JSONDecoder().decode(WeatherSettings.self, from: $0) }
        let migratedEncoded = legacyPortrait.flatMap { value in
            (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(value))) as? [String: Any]
        }
        var invalidScale = WeatherSettings()
        invalidScale.contentScale = .infinity
        invalidScale.normalize()
        var lowScale = WeatherSettings()
        lowScale.contentScale = 0.75
        lowScale.normalize()
        var highScale = WeatherSettings()
        highScale.contentScale = 2
        highScale.normalize()
        settings.showAllLocations = false
        let selectedLocations = settings.widgetLocations.map(\.id)
        settings.style = .icon
        return legacy?.portraitCards == false
            && legacy?.showAllLocations == false
            && legacy?.contentScale == 1.25
            && legacyPortrait?.style == .scenic && legacyPortrait?.portraitCards == true
            && migratedEncoded?["style"] as? String == "scenic" && migratedEncoded?["portraitCards"] as? Bool == true
            && allLocations == ["tokyo", "new-york"]
            && selectedLocations == ["new-york"]
            && settings.usesPortraitCards == false
            && roundTrip?.style == .compact && roundTrip?.portraitCards == true && roundTrip?.showAllLocations == true && roundTrip?.contentScale == 1.6
            && encoded?["portraitCards"] as? Bool == true
            && encoded?["showAllLocations"] as? Bool == true
            && encoded?["contentScale"] as? Double == 1.6
            && invalidScale.contentScale == 1.25
            && lowScale.contentScale == 1
            && highScale.contentScale == 1.6
            && WeatherContentSizing.selfTestsPass()
    }

    mutating func normalize() {
        var seenLocationIDs = Set<String>()
        locations = Array(locations.map { location in
            var normalized = location
            normalized.normalizeArtworkVariant()
            return normalized
        }.filter {
            seenLocationIDs.insert($0.id).inserted &&
            !$0.id.isEmpty && $0.id.count <= 80 &&
            !$0.name.isEmpty && $0.name.count <= 100 &&
            $0.country.count <= 100 &&
            $0.latitude.isFinite && (-90...90).contains($0.latitude) &&
            $0.longitude.isFinite && (-180...180).contains($0.longitude)
        }.prefix(6))

        if let selectedLocationId,
           !locations.contains(where: { $0.id == selectedLocationId && $0.enabled }) {
            self.selectedLocationId = locations.first(where: \.enabled)?.id
        } else if selectedLocationId == nil {
            selectedLocationId = locations.first(where: \.enabled)?.id
        }

        if WeatherCycle(rawValue: cycleSeconds) == nil { cycleSeconds = 0 }
        contentScale = WeatherSettings.normalizedContentScale(contentScale)
    }

    static func normalizedContentScale(_ value: Double) -> Double {
        guard value.isFinite else { return 1.25 }
        return min(max(value, 1), 1.6)
    }
}

struct WeatherSearchResult: Identifiable, Hashable {
    let id: String
    let name: String
    let country: String
    let administrativeArea: String?
    let latitude: Double
    let longitude: Double

    var subtitle: String {
        [administrativeArea, country]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }
}

struct WeatherHourly: Equatable, Identifiable {
    let time: Date
    let temperature: Double?
    let weatherCode: Int?
    let isDay: Bool?
    var id: Date { time }
}

struct WeatherDaily: Equatable, Identifiable {
    let time: Date
    let weatherCode: Int?
    let high: Double?
    let low: Double?
    var id: Date { time }
}

struct WeatherPreview: Equatable {
    let temperature: Double?
    let weatherCode: Int?
    let isDay: Bool?
    let updated: Date?
    let timezone: String?
    let unit: String
    let hourly: [WeatherHourly]
    let daily: [WeatherDaily]
}
