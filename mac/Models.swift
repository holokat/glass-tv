import Foundation
import CryptoKit

enum MediaKind: String, Codable, CaseIterable {
    case photo, video
}

enum VideoPlayback: String, Codable, CaseIterable, Identifiable {
    case cycle, loop
    var id: String { rawValue }
    var label: String { self == .cycle ? "Cycle library" : "Loop a video" }
}

enum FitMode: String, Codable, CaseIterable, Identifiable {
    case fill, fit
    var id: String { rawValue }
    var label: String { self == .fill ? "Fill" : "Fit" }
}

/// Launcher shortcuts are optional on the wire so older receivers retain their
/// existing launcher state until this Mac has been explicitly configured.
enum ShortcutStyle: String, Codable, CaseIterable, Equatable, Identifiable {
    case minimal, cards

    var id: String { rawValue }
    var title: String { self == .minimal ? "Minimal pills" : "Colorful cards" }
}

/// A streaming service that can be shown as a launcher shortcut. The shared
/// catalog supplies provider-specific package names and artwork; Mac retains
/// the legacy local shortcuts below for backwards-compatible settings.
struct AppShortcutDefinition: Codable, Equatable, Identifiable {
    let id: String
    let title: String
    let packages: [String]
    let minimalIcon: String
    let colorIcon: String
    let colorBackground: String
}

enum AppShortcutCatalog {
    /// Existing receivers and libraries use this sequence when an explicit
    /// order has not yet been saved.
    static let legacyOrder = ["netflix", "radio", "prime_video", "spotify", "twitch", "hulu", "movies", "hdmi"]
    static let legacy: [AppShortcutDefinition] = [
        AppShortcutDefinition(id: "netflix", title: "Netflix", packages: ["com.netflix.ninja"], minimalIcon: "netflix_logo", colorIcon: "brand_netflix_color", colorBackground: "#101010"),
        AppShortcutDefinition(id: "radio", title: "Radio", packages: [], minimalIcon: "radio", colorIcon: "radio", colorBackground: "#20252B"),
        AppShortcutDefinition(id: "prime_video", title: "Prime Video", packages: ["com.amazon.amazonvideo.livingroom"], minimalIcon: "prime_video_logo", colorIcon: "brand_prime_video_color", colorBackground: "#101010"),
        AppShortcutDefinition(id: "spotify", title: "Spotify", packages: ["com.spotify.tv.android"], minimalIcon: "spotify_logo", colorIcon: "brand_spotify_color", colorBackground: "#101010"),
        AppShortcutDefinition(id: "twitch", title: "Twitch", packages: ["tv.twitch.android.app"], minimalIcon: "twitch_logo", colorIcon: "brand_twitch_color", colorBackground: "#101010"),
        AppShortcutDefinition(id: "hulu", title: "Hulu", packages: ["com.hulu.livingroomplus"], minimalIcon: "hulu_logo", colorIcon: "brand_hulu_color", colorBackground: "#101010"),
        AppShortcutDefinition(id: "movies", title: "Movies", packages: [], minimalIcon: "movies", colorIcon: "movies", colorBackground: "#20252B")
    ]

    static var services: [AppShortcutDefinition] {
        guard let url = Bundle.main.url(forResource: "app-shortcuts", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? decode(data) else { return [] }
        return decoded
    }

    static func decode(_ data: Data) throws -> [AppShortcutDefinition] {
        let decoded = try JSONDecoder().decode([AppShortcutDefinition].self, from: data)
        var ids = Set<String>()
        for service in decoded {
            guard !service.id.isEmpty, !service.title.isEmpty,
                  !service.minimalIcon.isEmpty, !service.colorIcon.isEmpty,
                  !service.colorBackground.isEmpty, ids.insert(service.id).inserted else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Invalid app shortcut catalog"))
            }
        }
        return decoded
    }

    static func definitions(for settings: AppsSettings, includeDisabled: Bool = false, catalog: [AppShortcutDefinition] = services) -> [AppShortcutDefinition] {
        let hdmiIcon: String
        switch settings.hdmiStyle {
        case .switch: hdmiIcon = "switch_logo"
        case .ps5: hdmiIcon = "controller"
        case .hdmi: hdmiIcon = "hdmi"
        }
        let hdmi = AppShortcutDefinition(id: "hdmi", title: settings.hdmiStyle.label, packages: [], minimalIcon: hdmiIcon, colorIcon: hdmiIcon, colorBackground: "#454B5D")
        let all = legacy + catalog + [hdmi]
        let byID = Dictionary(all.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return settings.normalizedShortcutOrder(catalog: catalog).compactMap { id in
            guard let definition = byID[id], includeDisabled || settings.isShortcutEnabled(id) else { return nil }
            return definition
        }
    }
}

struct AppsSettings: Codable, Equatable {
    var layoutShortcutCount: Int {
        orderedShortcutIDs.filter(isShortcutEnabled).count
    }
    enum HDMIStyle: String, Codable, Equatable, CaseIterable, Identifiable {
        case `switch`, ps5, hdmi

        var id: String { rawValue }
        var label: String {
            switch self {
            case .switch: return "Nintendo Switch"
            case .ps5: return "PS5"
            case .hdmi: return "HDMI (any source)"
            }
        }
    }

    enum NavigationSound: String, Codable, Equatable, CaseIterable, Identifiable {
        case off, soft, glass, wood, bubble, chime

        var id: String { rawValue }
        var title: String {
            switch self {
            case .off: return "Off"
            case .soft: return "Soft"
            case .glass: return "Glass"
            case .wood: return "Wood"
            case .bubble: return "Bubble"
            case .chime: return "Chime"
            }
        }
    }

    var netflixEnabled: Bool = true
    var radioEnabled: Bool = true
    var hdmiEnabled: Bool = true
    var hdmiStyle: HDMIStyle = .switch
    var primeVideoEnabled: Bool = false
    var spotifyEnabled: Bool = false
    var twitchEnabled: Bool = false
    var huluEnabled: Bool = false
    /// Movie sharing is opt-in; older local settings deliberately migrate to off.
    var moviesEnabled: Bool = false
    /// Services supplied by shared/app-shortcuts.json. They start off for every
    /// library, including existing libraries that predate the catalog.
    var enabledServices: [String] = []
    var shortcutStyle: ShortcutStyle = .minimal
    /// Kept optional so receivers that do not understand ordering retain their
    /// legacy launcher arrangement until the user changes it on this Mac.
    var shortcutOrder: [String]? = nil
    /// Optional wire values preserve older receiver behavior until navigation
    /// feedback is explicitly configured on this Mac.
    var navigationSound: NavigationSound? = nil
    var navigationSoundVolume: Double? = nil

    var effectiveNavigationSound: NavigationSound { navigationSound ?? .off }
    var effectiveNavigationSoundVolume: Double { min(1, max(0, navigationSoundVolume ?? 0.25)) }

    init(netflixEnabled: Bool = true, radioEnabled: Bool = true, hdmiEnabled: Bool = true, hdmiStyle: HDMIStyle = .switch, primeVideoEnabled: Bool = false, spotifyEnabled: Bool = false, twitchEnabled: Bool = false, huluEnabled: Bool = false, moviesEnabled: Bool = false, enabledServices: [String] = [], shortcutStyle: ShortcutStyle = .minimal, shortcutOrder: [String]? = nil, navigationSound: NavigationSound? = nil, navigationSoundVolume: Double? = nil) {
        self.netflixEnabled = netflixEnabled
        self.radioEnabled = radioEnabled
        self.hdmiEnabled = hdmiEnabled
        self.hdmiStyle = hdmiStyle
        self.primeVideoEnabled = primeVideoEnabled
        self.spotifyEnabled = spotifyEnabled
        self.twitchEnabled = twitchEnabled
        self.huluEnabled = huluEnabled
        self.moviesEnabled = moviesEnabled
        self.enabledServices = Array(NSOrderedSet(array: enabledServices)) as? [String] ?? []
        self.shortcutStyle = shortcutStyle
        self.shortcutOrder = shortcutOrder
        self.navigationSound = navigationSound
        self.navigationSoundVolume = navigationSoundVolume.map { min(1, max(0, $0)) }
        if shortcutOrder != nil { self.shortcutOrder = normalizedShortcutOrder() }
    }

    private enum CodingKeys: String, CodingKey { case netflixEnabled, radioEnabled, hdmiEnabled, hdmiStyle, primeVideoEnabled, spotifyEnabled, twitchEnabled, huluEnabled, moviesEnabled, enabledServices, shortcutStyle, shortcutOrder, navigationSound, navigationSoundVolume }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        netflixEnabled = try values.decodeIfPresent(Bool.self, forKey: .netflixEnabled) ?? true
        radioEnabled = try values.decodeIfPresent(Bool.self, forKey: .radioEnabled) ?? true
        hdmiEnabled = try values.decodeIfPresent(Bool.self, forKey: .hdmiEnabled) ?? true
        hdmiStyle = try values.decodeIfPresent(HDMIStyle.self, forKey: .hdmiStyle) ?? .switch
        primeVideoEnabled = try values.decodeIfPresent(Bool.self, forKey: .primeVideoEnabled) ?? false
        spotifyEnabled = try values.decodeIfPresent(Bool.self, forKey: .spotifyEnabled) ?? false
        twitchEnabled = try values.decodeIfPresent(Bool.self, forKey: .twitchEnabled) ?? false
        huluEnabled = try values.decodeIfPresent(Bool.self, forKey: .huluEnabled) ?? false
        moviesEnabled = try values.decodeIfPresent(Bool.self, forKey: .moviesEnabled) ?? false
        enabledServices = Array(NSOrderedSet(array: try values.decodeIfPresent([String].self, forKey: .enabledServices) ?? [])) as? [String] ?? []
        shortcutStyle = try values.decodeIfPresent(ShortcutStyle.self, forKey: .shortcutStyle) ?? .minimal
        shortcutOrder = try values.decodeIfPresent([String].self, forKey: .shortcutOrder)
        let soundID: String? = (try? values.decodeIfPresent(String.self, forKey: .navigationSound)) ?? nil
        navigationSound = soundID.flatMap(NavigationSound.init(rawValue:))
        let volume: Double? = (try? values.decodeIfPresent(Double.self, forKey: .navigationSoundVolume)) ?? nil
        navigationSoundVolume = volume.map { min(1, max(0, $0)) }
        if shortcutOrder != nil { shortcutOrder = normalizedShortcutOrder() }
    }

    func isServiceEnabled(_ id: String) -> Bool { enabledServices.contains(id) }

    mutating func setService(_ id: String, enabled: Bool) {
        let retained = enabledServices.filter { $0 != id }
        enabledServices = enabled ? retained + [id] : retained
    }

    /// A stable, complete ordering. Unknown IDs and duplicate entries are
    /// discarded, and newly catalogued shortcuts join at the end.
    func normalizedShortcutOrder(catalog: [AppShortcutDefinition] = AppShortcutCatalog.services) -> [String] {
        let canonical = AppShortcutCatalog.legacyOrder + catalog.map(\.id).filter { !AppShortcutCatalog.legacyOrder.contains($0) }
        var seen = Set<String>()
        let saved = (shortcutOrder ?? AppShortcutCatalog.legacyOrder).filter { canonical.contains($0) && seen.insert($0).inserted }
        return saved + canonical.filter { seen.insert($0).inserted }
    }

    var orderedShortcutIDs: [String] { normalizedShortcutOrder() }

    func isShortcutEnabled(_ id: String) -> Bool {
        switch id {
        case "netflix": return netflixEnabled
        case "radio": return radioEnabled
        case "prime_video": return primeVideoEnabled
        case "spotify": return spotifyEnabled
        case "twitch": return twitchEnabled
        case "hulu": return huluEnabled
        case "movies": return moviesEnabled
        case "hdmi": return hdmiEnabled
        default: return isServiceEnabled(id)
        }
    }

    mutating func moveShortcutOrder(from source: IndexSet, to destination: Int, catalog: [AppShortcutDefinition] = AppShortcutCatalog.services) {
        var order = normalizedShortcutOrder(catalog: catalog)
        let moving = source.map { order[$0] }
        for index in source.sorted(by: >) { order.remove(at: index) }
        let removedBeforeDestination = source.filter { $0 < destination }.count
        order.insert(contentsOf: moving, at: max(0, min(order.count, destination - removedBeforeDestination)))
        shortcutOrder = order
    }

    mutating func moveShortcut(id: String, by offset: Int, catalog: [AppShortcutDefinition] = AppShortcutCatalog.services) {
        var order = normalizedShortcutOrder(catalog: catalog)
        guard let index = order.firstIndex(of: id) else { return }
        let target = max(0, min(order.count - 1, index + offset))
        guard target != index else { return }
        order.swapAt(index, target)
        shortcutOrder = order
    }

    /// Dropping downward places an item after its target; dropping upward places
    /// it before its target. This does not mutate visibility settings.
    mutating func moveShortcut(_ id: String, onto destinationID: String, catalog: [AppShortcutDefinition] = AppShortcutCatalog.services) {
        var order = normalizedShortcutOrder(catalog: catalog)
        guard id != destinationID,
              let source = order.firstIndex(of: id),
              let destination = order.firstIndex(of: destinationID) else { return }
        order.remove(at: source)
        order.insert(id, at: min(order.count, destination))
        shortcutOrder = order
    }
}

struct PlaylistImage: Codable, Hashable, Identifiable {
    let id: String
    var name: String
    var kind: MediaKind? = nil
}

struct Playlist: Codable {
    var intervalSeconds: Int
    var shuffle: Bool
    var images: [PlaylistImage]
    var videoPlayback: VideoPlayback? = nil
    var loopVideoId: String? = nil
    var muteVideos: Bool? = nil
    var slideshowEnabled: Bool? = nil
    var fitMode: FitMode? = nil
    var showTVInterface: Bool? = nil
    var weather: WeatherSettings? = nil
    var apps: AppsSettings? = nil
    /// Optional placement data. Older receivers ignore this field and retain their
    /// launcher arrangement until a Layout update is explicitly sent.
    var layout: LayoutSettings? = nil
}

struct StoredImage: Codable, Hashable {
    let id: String
    let bytes: Int
    let width: Int
    let height: Int
    var kind: MediaKind? = nil
    var durationSeconds: Double? = nil
}

struct StorageInfo: Codable { let usedBytes: Int; let maxBytes: Int }
struct ServerLimits: Codable { let maxImages: Int; let maxImageBytes: Int; let minIntervalSeconds: Int; let maxIntervalSeconds: Int }
struct TVInput: Codable, Identifiable, Hashable { let id: String; let parentId: String?; let label: String; let customLabel: String?; let state: String }

struct ServerStatus: Codable {
    let apiVersion: Int
    let deviceId: String
    let deviceName: String?
    let manufacturer: String?
    let model: String?
    let revision: Int
    let playlist: Playlist
    let storedImages: [StoredImage]
    let storage: StorageInfo
    let limits: ServerLimits
    let inputs: [TVInput]
    let selectedInputId: String?
}

struct PairReply: Codable { let apiVersion: Int; let deviceId: String; let token: String; let tokenType: String }
struct RevisionReply: Codable { let revision: Int }
struct APIErrorEnvelope: Codable { let error: APIError }
struct APIError: Codable { let code: String; let message: String }

struct LocalPhoto: Codable, Identifiable, Hashable {
    let uuid: UUID
    var digest: String
    var name: String
    var fileName: String
    var addedAt: Date
    var inRotation: Bool
    var kind: MediaKind? = nil
    var durationSeconds: Double? = nil
    var byteCount: Int? = nil
    var previewFileName: String? = nil
    /// A shared compatibility-cache file. Existing imports remain Photos-relative.
    var compatibleVideoURL: URL? = nil
    var id: UUID { uuid }
    var mediaKind: MediaKind { kind ?? .photo }
}

struct LocalSettings: Codable {
    var intervalSeconds: Int
    var shuffle: Bool
    var selectedInputId: String?
    var hasConfiguredInput: Bool
    var videoPlayback: VideoPlayback
    var loopVideoId: String?
    var muteVideos: Bool
    var slideshowEnabled: Bool
    var fitMode: FitMode
    var showTVInterface: Bool
    var hasConfiguredShowTVInterface: Bool
    var photos: [LocalPhoto]
    var weather: WeatherSettings
    var hasConfiguredWeather: Bool
    /// Only edits made locally since the last accepted receiver state are delivered back to TV.
    /// Older saved preferences deliberately start false so reconnecting does not overwrite a
    /// standalone-TV change with an old Mac snapshot.
    var pendingWeatherSync: Bool
    var apps: AppsSettings
    var hasConfiguredApps: Bool
    var pendingAppsSync: Bool
    var pendingInputSync: Bool
    /// Layout edits are deliberately local until the user selects Update TV.
    var layoutDraft: LayoutSettings
    var appliedLayout: LayoutSettings
    var hasConfiguredLayout: Bool

    init(intervalSeconds: Int = 300, shuffle: Bool = false, selectedInputId: String? = nil, hasConfiguredInput: Bool = false, videoPlayback: VideoPlayback = .cycle, loopVideoId: String? = nil, muteVideos: Bool = true, slideshowEnabled: Bool = true, fitMode: FitMode = .fill, showTVInterface: Bool = true, hasConfiguredShowTVInterface: Bool = false, photos: [LocalPhoto] = [], weather: WeatherSettings = WeatherSettings(), hasConfiguredWeather: Bool = false, pendingWeatherSync: Bool = false, apps: AppsSettings = AppsSettings(), hasConfiguredApps: Bool = false, pendingAppsSync: Bool = false, pendingInputSync: Bool = false, layoutDraft: LayoutSettings = LayoutSettings(), appliedLayout: LayoutSettings = LayoutSettings(), hasConfiguredLayout: Bool = false) {
        self.intervalSeconds = intervalSeconds; self.shuffle = shuffle; self.selectedInputId = selectedInputId
        self.hasConfiguredInput = hasConfiguredInput
        self.videoPlayback = videoPlayback; self.loopVideoId = loopVideoId; self.muteVideos = muteVideos; self.slideshowEnabled = slideshowEnabled; self.fitMode = fitMode; self.showTVInterface = showTVInterface; self.hasConfiguredShowTVInterface = hasConfiguredShowTVInterface; self.photos = photos; self.weather = weather; self.hasConfiguredWeather = hasConfiguredWeather
        self.pendingWeatherSync = pendingWeatherSync
        self.apps = apps; self.hasConfiguredApps = hasConfiguredApps; self.pendingAppsSync = pendingAppsSync; self.pendingInputSync = pendingInputSync
        self.layoutDraft = layoutDraft; self.appliedLayout = appliedLayout; self.hasConfiguredLayout = hasConfiguredLayout
    }

    private enum CodingKeys: String, CodingKey { case intervalSeconds, shuffle, selectedInputId, hasConfiguredInput, videoPlayback, loopVideoId, muteVideos, slideshowEnabled, fitMode, showTVInterface, hasConfiguredShowTVInterface, photos, weather, hasConfiguredWeather, pendingWeatherSync, apps, hasConfiguredApps, pendingAppsSync, pendingInputSync, layoutDraft, appliedLayout, hasConfiguredLayout }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        intervalSeconds = try values.decodeIfPresent(Int.self, forKey: .intervalSeconds) ?? 300
        shuffle = try values.decodeIfPresent(Bool.self, forKey: .shuffle) ?? false
        selectedInputId = try values.decodeIfPresent(String.self, forKey: .selectedInputId)
        hasConfiguredInput = try values.decodeIfPresent(Bool.self, forKey: .hasConfiguredInput) ?? false
        videoPlayback = try values.decodeIfPresent(VideoPlayback.self, forKey: .videoPlayback) ?? .cycle
        loopVideoId = try values.decodeIfPresent(String.self, forKey: .loopVideoId)
        muteVideos = try values.decodeIfPresent(Bool.self, forKey: .muteVideos) ?? true
        slideshowEnabled = try values.decodeIfPresent(Bool.self, forKey: .slideshowEnabled) ?? true
        fitMode = try values.decodeIfPresent(FitMode.self, forKey: .fitMode) ?? .fill
        showTVInterface = try values.decodeIfPresent(Bool.self, forKey: .showTVInterface) ?? true
        hasConfiguredShowTVInterface = try values.decodeIfPresent(Bool.self, forKey: .hasConfiguredShowTVInterface) ?? false
        photos = try values.decodeIfPresent([LocalPhoto].self, forKey: .photos) ?? []
        weather = try values.decodeIfPresent(WeatherSettings.self, forKey: .weather) ?? WeatherSettings()
        hasConfiguredWeather = try values.decodeIfPresent(Bool.self, forKey: .hasConfiguredWeather) ?? false
        pendingWeatherSync = try values.decodeIfPresent(Bool.self, forKey: .pendingWeatherSync) ?? false
        apps = try values.decodeIfPresent(AppsSettings.self, forKey: .apps) ?? AppsSettings()
        hasConfiguredApps = try values.decodeIfPresent(Bool.self, forKey: .hasConfiguredApps) ?? false
        pendingAppsSync = try values.decodeIfPresent(Bool.self, forKey: .pendingAppsSync) ?? false
        pendingInputSync = try values.decodeIfPresent(Bool.self, forKey: .pendingInputSync) ?? false
        layoutDraft = try values.decodeIfPresent(LayoutSettings.self, forKey: .layoutDraft) ?? LayoutSettings()
        appliedLayout = try values.decodeIfPresent(LayoutSettings.self, forKey: .appliedLayout) ?? LayoutSettings()
        hasConfiguredLayout = try values.decodeIfPresent(Bool.self, forKey: .hasConfiguredLayout) ?? false
    }
}

struct PairCredential: Codable {
    let host: String
    let deviceId: String
    let token: String
    let certificatePin: String
}

enum RotationInterval: Int, CaseIterable, Identifiable {
    case seconds10 = 10, seconds30 = 30, minute1 = 60, minutes5 = 300, minutes15 = 900, minutes30 = 1800, hour1 = 3600, hours6 = 21600, hours12 = 43200, day1 = 86400
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .seconds10: return "10 seconds"
        case .seconds30: return "30 seconds"
        case .minute1: return "1 minute"
        case .minutes5: return "5 minutes"
        case .minutes15: return "15 minutes"
        case .minutes30: return "30 minutes"
        case .hour1: return "1 hour"
        case .hours6: return "6 hours"
        case .hours12: return "12 hours"
        case .day1: return "24 hours"
        }
    }
}

func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

func groupedFingerprint(_ pin: String) -> String {
    pin.uppercased().chunked(into: 4).joined(separator: " ")
}

extension Collection {
    func chunked(into size: Int) -> [SubSequence] {
        var chunks: [SubSequence] = []
        var start = startIndex
        while start != endIndex {
            let end = index(start, offsetBy: size, limitedBy: endIndex) ?? endIndex
            chunks.append(self[start..<end])
            start = end
        }
        return chunks
    }
}

enum ProtocolSelfTest {
    static func run() throws {
        try MovieCollectionChecks.run()
        try MovieLibraryBridgeChecks.run()
        let legacySettings = try JSONDecoder().decode(LocalSettings.self, from: Data("{}".utf8))
        let pendingSettings = try JSONDecoder().decode(LocalSettings.self, from: JSONEncoder().encode(LocalSettings(pendingWeatherSync: true, pendingAppsSync: true, pendingInputSync: true)))
        let legacyPlaylist = try JSONDecoder().decode(Playlist.self, from: Data("{\"intervalSeconds\":300,\"shuffle\":false,\"images\":[]}".utf8))
        let namedStatus = try JSONDecoder().decode(ServerStatus.self, from: Data("{\"apiVersion\":1,\"deviceId\":\"receiver-1\",\"deviceName\":\"Living room TV\",\"manufacturer\":\"Sony\",\"model\":\"BRAVIA\",\"revision\":0,\"playlist\":{\"intervalSeconds\":300,\"shuffle\":false,\"images\":[]},\"storedImages\":[],\"storage\":{\"usedBytes\":0,\"maxBytes\":1},\"limits\":{\"maxImages\":1,\"maxImageBytes\":1,\"minIntervalSeconds\":1,\"maxIntervalSeconds\":1},\"inputs\":[],\"selectedInputId\":null}".utf8))
        let encodedSettings = try JSONSerialization.jsonObject(with: JSONEncoder().encode(LocalSettings())) as? [String: Any]
        let encodedDisabledSettings = try JSONSerialization.jsonObject(with: JSONEncoder().encode(LocalSettings(showTVInterface: false))) as? [String: Any]
        let disabledSettings = try JSONDecoder().decode(LocalSettings.self, from: JSONEncoder().encode(LocalSettings(showTVInterface: false)))
        let receiverDisabled = try JSONDecoder().decode(Playlist.self, from: Data("{\"intervalSeconds\":300,\"shuffle\":false,\"images\":[],\"showTVInterface\":false}".utf8))
        let receiverEnabled = try JSONDecoder().decode(Playlist.self, from: Data("{\"intervalSeconds\":300,\"shuffle\":false,\"images\":[],\"showTVInterface\":true}".utf8))
        let receiverApps = try JSONDecoder().decode(Playlist.self, from: Data("{\"intervalSeconds\":300,\"shuffle\":false,\"images\":[],\"apps\":{\"netflixEnabled\":false,\"radioEnabled\":true,\"hdmiEnabled\":true,\"hdmiStyle\":\"ps5\"}}".utf8))
        let receiverLayout = try JSONDecoder().decode(Playlist.self, from: Data("{\"intervalSeconds\":300,\"shuffle\":false,\"images\":[],\"layout\":{\"version\":1,\"wifiEnabled\":false,\"clock\":{\"x\":0.1,\"y\":0.2}}}".utf8))
        let layoutWire = receiverLayout.layout?.wireObject()
        let appsRoundTrip = try JSONDecoder().decode(LocalSettings.self, from: JSONEncoder().encode(LocalSettings(apps: AppsSettings(netflixEnabled: false, radioEnabled: false, hdmiEnabled: true, hdmiStyle: .hdmi, primeVideoEnabled: true, spotifyEnabled: true, twitchEnabled: true, huluEnabled: true, moviesEnabled: true), hasConfiguredApps: true)))
        let catalogData = Data("[{\"id\":\"youtube\",\"title\":\"YouTube\",\"packages\":[\"com.google.android.youtube.tv\"],\"minimalIcon\":\"brand_youtube\",\"colorIcon\":\"brand_youtube_color\",\"colorBackground\":\"#FF0000\"}]".utf8)
        let catalog = try AppShortcutCatalog.decode(catalogData)
        let expandedApps = AppsSettings(enabledServices: ["youtube", "youtube", "tubi"], shortcutStyle: .cards)
        let expandedAppsRoundTrip = try JSONDecoder().decode(AppsSettings.self, from: JSONEncoder().encode(expandedApps))
        let orderedApps = AppsSettings(netflixEnabled: false, radioEnabled: false, hdmiEnabled: false, shortcutOrder: ["unknown", "spotify", "spotify", "netflix"])
        let normalizedOrder = orderedApps.normalizedShortcutOrder(catalog: catalog)
        let suppliedOrder = try JSONDecoder().decode(AppsSettings.self, from: Data("{\"shortcutOrder\":[\"spotify\",\"spotify\",\"unknown\",\"netflix\"]}".utf8))
        let navigationSoundSettings = try JSONDecoder().decode(AppsSettings.self, from: Data("{\"navigationSound\":\"glass\",\"navigationSoundVolume\":0.8}".utf8))
        let invalidNavigationSoundSettings = try JSONDecoder().decode(AppsSettings.self, from: Data("{\"navigationSound\":\"unsupported\",\"navigationSoundVolume\":1.5}".utf8))
        let legacyNavigationSoundSettings = try JSONDecoder().decode(AppsSettings.self, from: Data("{}".utf8))
        let encodedNavigationSound = try JSONSerialization.jsonObject(with: JSONEncoder().encode(AppsSettings(navigationSound: .chime, navigationSoundVolume: 0.25))) as? [String: Any]
        var movedOrder = AppsSettings(shortcutOrder: ["netflix", "radio"])
        movedOrder.moveShortcut(id: "radio", by: -1, catalog: catalog)
        var dropOrder = AppsSettings(shortcutOrder: AppShortcutCatalog.legacyOrder)
        dropOrder.moveShortcut("netflix", onto: "hdmi", catalog: [])
        dropOrder.moveShortcut("hdmi", onto: "spotify", catalog: [])
        guard sha256Hex(Data("Glass TV".utf8)).count == 64,
              ConversionCache.safetyChecksPass(),
              groupedFingerprint(String(repeating: "a", count: 64)) == "AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA",
              RotationInterval(rawValue: 300)?.label == "5 minutes",
              movieTitleAndYear(from: "1917.2019.1080p.mkv") == ("1917", 2019),
              movieTitleAndYear(from: "2001.A.Space.Odyssey.1968.mkv") == ("2001 A Space Odyssey", 1968),
              movieTitleAndYear(from: "Arrival.2016.2160p.mkv") == ("Arrival", 2016),
              MovieFileKind.accepts(URL(fileURLWithPath: "/Movies/8k Underwater.mkv")),
              MovieFileKind.accepts(URL(fileURLWithPath: "/Movies/clip.webm")),
              MovieFileKind.shouldProbe(URL(fileURLWithPath: "/Movies/custom-container.video")),
              !MovieFileKind.shouldProbe(URL(fileURLWithPath: "/Movies/cover.jpg")),
              legacySettings.showTVInterface,
              !legacySettings.hasConfiguredShowTVInterface,
              !legacySettings.hasConfiguredInput,
              !legacySettings.hasConfiguredApps,
              !legacySettings.pendingWeatherSync,
              !legacySettings.pendingAppsSync,
              !legacySettings.pendingInputSync,
              pendingSettings.pendingWeatherSync,
              pendingSettings.pendingAppsSync,
              pendingSettings.pendingInputSync,
              legacySettings.apps == AppsSettings(),
              legacySettings.apps.enabledServices.isEmpty,
              legacySettings.apps.shortcutStyle == .minimal,
              legacyPlaylist.showTVInterface == nil,
              legacyPlaylist.apps == nil,
              legacyPlaylist.layout == nil,
              namedStatus.deviceName == "Living room TV",
              namedStatus.manufacturer == "Sony",
              namedStatus.model == "BRAVIA",
              !disabledSettings.showTVInterface,
              receiverDisabled.showTVInterface == false,
              receiverEnabled.showTVInterface == true,
              receiverApps.apps == AppsSettings(netflixEnabled: false, radioEnabled: true, hdmiEnabled: true, hdmiStyle: .ps5),
              receiverLayout.layout?.clock == LayoutPosition(x: 0.1, y: 0.2),
              receiverLayout.layout?.wifiEnabled == false,
              layoutWire?["version"] as? Int == 1,
              layoutWire?["weatherStyle"] == nil,
              LayoutGeometry.selfTestsPass(),
              appsRoundTrip.hasConfiguredApps,
              appsRoundTrip.apps == AppsSettings(netflixEnabled: false, radioEnabled: false, hdmiEnabled: true, hdmiStyle: .hdmi, primeVideoEnabled: true, spotifyEnabled: true, twitchEnabled: true, huluEnabled: true, moviesEnabled: true),
              catalog == [AppShortcutDefinition(id: "youtube", title: "YouTube", packages: ["com.google.android.youtube.tv"], minimalIcon: "brand_youtube", colorIcon: "brand_youtube_color", colorBackground: "#FF0000")],
              expandedApps.enabledServices == ["youtube", "tubi"],
              expandedAppsRoundTrip == expandedApps,
              normalizedOrder == ["spotify", "netflix", "radio", "prime_video", "twitch", "hulu", "movies", "hdmi", "youtube"],
              suppliedOrder.shortcutOrder.map({ Array($0.prefix(2)) }) == ["spotify", "netflix"],
              suppliedOrder.shortcutOrder?.contains("unknown") == false,
              navigationSoundSettings.navigationSound == .glass,
              navigationSoundSettings.navigationSoundVolume == 0.8,
              invalidNavigationSoundSettings.navigationSound == nil,
              invalidNavigationSoundSettings.navigationSoundVolume == 1,
              legacyNavigationSoundSettings.navigationSound == nil,
              legacyNavigationSoundSettings.navigationSoundVolume == nil,
              legacyNavigationSoundSettings.effectiveNavigationSound == .off,
              legacyNavigationSoundSettings.effectiveNavigationSoundVolume == 0.25,
              encodedNavigationSound?["navigationSound"] as? String == "chime",
              encodedNavigationSound?["navigationSoundVolume"] as? Double == 0.25,
              movedOrder.shortcutOrder.map({ Array($0.prefix(2)) }) == ["radio", "netflix"],
              dropOrder.shortcutOrder == ["radio", "prime_video", "hdmi", "spotify", "twitch", "hulu", "movies", "netflix"],
              AppsSyncScheduling.selfTestsPass(),
              SettingsSyncPolicy.selfTestsPass(),
              TVAutomaticReconnectPolicy.selfTestsPass(),
              TVNetworkRecoveryPolicy.selfTestsPass(),
              WeatherSettings.selfTestsPass(),
              WeatherArtwork.selfTestsPass(),
              TVProfiles.selfTestsPass(),
              AppDiagnostics.selfTestsPass(),
              encodedSettings?["showTVInterface"] as? Bool == true,
              encodedDisabledSettings?["showTVInterface"] as? Bool == false,
              TVConnectionState.resolve(activity: .checking, isPaired: true, hasCertificateChallenge: false, hasAuthenticatedStatus: true) == .connected,
              TVConnectionState.resolve(activity: .checking, isPaired: true, hasCertificateChallenge: false, hasAuthenticatedStatus: false) == .checking,
              TVConnectionActionPolicy.canSync(state: .connected, isPaired: true, hasAuthenticatedStatus: true, isBusy: false, hasActiveMedia: true),
              !TVConnectionActionPolicy.canSync(state: .checking, isPaired: true, hasAuthenticatedStatus: true, isBusy: false, hasActiveMedia: true),
              !TVConnectionActionPolicy.canSync(state: .connected, isPaired: true, hasAuthenticatedStatus: true, isBusy: true, hasActiveMedia: true),
              !TVConnectionActionPolicy.canAutoRefresh(isPaired: true, isBusy: false, hasPairingChallenge: true, isLocallyDisconnected: false),
              !TVConnectionActionPolicy.canAutoRefresh(isPaired: true, isBusy: false, hasPairingChallenge: false, isLocallyDisconnected: true),
              !TVConnectionActionPolicy.canAutoRefresh(isPaired: true, isBusy: true, hasPairingChallenge: false, isLocallyDisconnected: false) else { throw SelfTestError.failed }
    }
    enum SelfTestError: LocalizedError { case failed; var errorDescription: String? { "Glass TV protocol self-test failed." } }
}
