import Foundation

/// A receiver confirmed by pairing or an authenticated status response. `name` is
/// user supplied; `deviceName` is the optional receiver-reported name.
struct TVProfileDescriptor: Codable, Equatable, Identifiable {
    let id: String
    var host: String
    var name: String?
    var deviceName: String?
    var manufacturer: String?
    var model: String?

    var displayName: String { name ?? deviceName ?? host }
}

struct TVProfile: Codable, Identifiable {
    var descriptor: TVProfileDescriptor
    var settings: LocalSettings

    var id: String { descriptor.id }
}

/// A local pairing draft is intentionally distinct from saved receiver entries.
/// It keeps offline work safe without displaying an unverified TV as saved.
struct PendingTVProfile: Codable {
    var host: String
    var name: String?
    var settings: LocalSettings
}

/// Settings are isolated per receiver. Imported media files remain in the
/// shared Photos directory; this document maintains a common catalogue while
/// each profile retains its own ordering and rotation state.
struct TVProfiles: Codable {
    var activeID: String?
    var activeIsPending: Bool
    var profiles: [TVProfile]
    var pending: PendingTVProfile?
    var sharedPhotos: [LocalPhoto]

    init(activeID: String? = nil, activeIsPending: Bool = false, profiles: [TVProfile] = [], pending: PendingTVProfile? = nil, sharedPhotos: [LocalPhoto] = []) {
        self.activeID = activeID
        self.activeIsPending = activeIsPending
        self.profiles = profiles
        self.pending = pending
        self.sharedPhotos = sharedPhotos
    }

    private enum CodingKeys: String, CodingKey { case activeID, activeIsPending, profiles, pending, sharedPhotos }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        activeID = try values.decodeIfPresent(String.self, forKey: .activeID)
        activeIsPending = try values.decodeIfPresent(Bool.self, forKey: .activeIsPending) ?? false
        profiles = try values.decodeIfPresent([TVProfile].self, forKey: .profiles) ?? []
        pending = try values.decodeIfPresent(PendingTVProfile.self, forKey: .pending)
        sharedPhotos = try values.decodeIfPresent([LocalPhoto].self, forKey: .sharedPhotos) ?? []
    }

    func profile(host: String) -> TVProfile? {
        profiles.first { $0.descriptor.host.caseInsensitiveCompare(host) == .orderedSame }
    }

    /// Device identity, not a reused DHCP address, establishes an alias.
    func profileIndex(deviceID: String) -> Int? {
        profiles.firstIndex { $0.id == deviceID }
    }

    func settings(for profile: TVProfile) -> LocalSettings {
        var result = profile.settings
        let known = Dictionary(uniqueKeysWithValues: result.photos.map { ($0.digest, $0) })
        let shared = Dictionary(uniqueKeysWithValues: sharedPhotos.map { ($0.digest, $0) })
        var merged: [LocalPhoto] = result.photos.compactMap { saved in
            guard var latest = shared[saved.digest] else { return saved }
            latest.inRotation = saved.inRotation
            return latest
        }
        let seen = Set(merged.map(\.digest))
        for var photo in sharedPhotos where !seen.contains(photo.digest) {
            photo.inRotation = false
            merged.append(photo)
        }
        // Preserve profile-local photo records if a shared catalogue was only
        // partially written before a previous app quit.
        if known.count > merged.count {
            merged.append(contentsOf: result.photos.filter { photo in !merged.contains(where: { $0.digest == photo.digest }) })
        }
        result.photos = merged
        _ = DefaultBackground.seed(into: &result)
        return result
    }

    mutating func save(_ settings: LocalSettings, for id: String) {
        guard let index = profiles.firstIndex(where: { $0.id == id }) else { return }
        profiles[index].settings = settings
        mergeSharedPhotos(settings.photos)
    }

    mutating func mergeSharedPhotos(_ photos: [LocalPhoto]) {
        var indexByDigest = Dictionary(uniqueKeysWithValues: sharedPhotos.enumerated().map { ($0.element.digest, $0.offset) })
        for photo in photos {
            if let index = indexByDigest[photo.digest] {
                sharedPhotos[index] = photo
            } else {
                indexByDigest[photo.digest] = sharedPhotos.count
                sharedPhotos.append(photo)
            }
        }
    }

    func freshSettings() -> LocalSettings {
        var settings = LocalSettings()
        settings.photos = sharedPhotos.map { photo in
            var copy = photo
            copy.inRotation = false
            return copy
        }
        // A new TV can share the bundled record without inheriting another TV's
        // stored-only state. Select it only when there are no imported choices.
        if settings.defaultBackgroundEnabled,
           !settings.photos.contains(where: { !DefaultBackground.isBundled($0) }) {
            for index in settings.photos.indices where DefaultBackground.isBundled(settings.photos[index]) {
                settings.photos[index].inRotation = true
            }
        }
        _ = DefaultBackground.seed(into: &settings)
        return settings
    }

    /// Profiles own their rotation choices, so every profile receives the bundled
    /// item independently. Existing choices stay intact and the default remains
    /// stored-only when that profile already has media.
    @discardableResult
    mutating func seedDefaultBackgrounds() -> Bool {
        var changed = false
        for index in profiles.indices {
            changed = DefaultBackground.seed(into: &profiles[index].settings) || changed
        }
        if var pending {
            let pendingChanged = DefaultBackground.seed(into: &pending.settings)
            self.pending = pending
            changed = pendingChanged || changed
        }
        return changed
    }

    static func migratingLegacy(_ settings: LocalSettings, host: String, deviceID: String) -> TVProfiles {
        let profile = TVProfile(
            descriptor: TVProfileDescriptor(id: deviceID, host: host, name: nil, deviceName: nil, manufacturer: nil, model: nil),
            settings: settings
        )
        return TVProfiles(activeID: deviceID, profiles: [profile], sharedPhotos: settings.photos)
    }

    static func selfTestsPass() -> Bool {
        var legacy = LocalSettings()
        legacy.shuffle = true
        legacy.layoutDraft.clock = LayoutPosition(x: 0.1, y: 0.2)
        let migrated = migratingLegacy(legacy, host: "192.0.2.9", deviceID: "device-legacy")
        let one = LocalPhoto(uuid: UUID(), digest: "one", name: "One", fileName: "one.jpg", addedAt: .distantPast, inRotation: true)
        let two = LocalPhoto(uuid: UUID(), digest: "two", name: "Two", fileName: "two.jpg", addedAt: .distantPast, inRotation: true)
        let first = TVProfile(descriptor: TVProfileDescriptor(id: "device-living", host: "192.0.2.10", name: "Living room", deviceName: "TV", manufacturer: nil, model: nil), settings: LocalSettings(photos: [one]))
        var secondSettings = LocalSettings(photos: [one])
        secondSettings.photos[0].inRotation = false
        let second = TVProfile(descriptor: TVProfileDescriptor(id: "device-bedroom", host: "192.0.2.11", name: nil, deviceName: "Bedroom", manufacturer: nil, model: nil), settings: secondSettings)
        var profiles = TVProfiles(activeID: first.id, profiles: [first, second], sharedPhotos: [one])
        profiles.mergeSharedPhotos([two])
        let mergedSecond = profiles.settings(for: second)
        let fresh = profiles.freshSettings()
        let pending = PendingTVProfile(host: "192.0.2.12", name: "Office", settings: fresh)
        profiles.pending = pending
        profiles.activeID = nil
        profiles.activeIsPending = true
        var updatedFirst = profiles.settings(for: first)
        updatedFirst.shuffle = true
        profiles.save(updatedFirst, for: first.id)
        var freshDefault = LocalSettings()
        let seededFresh = DefaultBackground.seed(into: &freshDefault)
        let reseededFresh = DefaultBackground.seed(into: &freshDefault)
        var existingChoices = LocalSettings(photos: [one])
        let seededExisting = DefaultBackground.seed(into: &existingChoices)
        let defaultOnlyProfile = TVProfiles(sharedPhotos: [DefaultBackground.photo(inRotation: false)]).freshSettings()
        var removedDefault = freshDefault
        removedDefault.defaultBackgroundEnabled = false
        removedDefault.photos.removeAll(where: DefaultBackground.isBundled)
        let reseededRemoved = DefaultBackground.seed(into: &removedDefault)
        var duplicateDefault = LocalSettings(photos: [DefaultBackground.photo(inRotation: true), DefaultBackground.photo(inRotation: false)])
        _ = DefaultBackground.seed(into: &duplicateDefault)
        return migrated.activeID == "device-legacy"
            && migrated.profiles.first?.descriptor.host == "192.0.2.9"
            && migrated.profiles.first?.settings.shuffle == true
            && migrated.profiles.first?.settings.layoutDraft.clock == LayoutPosition(x: 0.1, y: 0.2)
            && first.descriptor.displayName == "Living room"
            && second.descriptor.displayName == "Bedroom"
            && mergedSecond.photos.map(\.digest) == ["one", "two", DefaultBackground.digest]
            && !mergedSecond.photos[0].inRotation && !mergedSecond.photos[1].inRotation && !mergedSecond.photos[2].inRotation
            && fresh.photos.map(\.inRotation) == [false, false, false]
            && !fresh.hasConfiguredApps && !fresh.hasConfiguredWeather && !fresh.hasConfiguredLayout
            && profiles.activeIsPending && profiles.pending?.host == "192.0.2.12"
            && profiles.profiles.first(where: { $0.id == first.id })?.settings.shuffle == true
            && profiles.profiles.first(where: { $0.id == first.id }).map(profiles.settings(for:))?.shuffle == true
            && profiles.profile(host: "192.0.2.11")?.id == second.id
            && profiles.profileIndex(deviceID: "device-living") == 0
            && profiles.profileIndex(deviceID: "unknown") == nil
            && seededFresh && !reseededFresh
            && freshDefault.photos.count == 1 && freshDefault.photos[0].inRotation
            && seededExisting && existingChoices.photos.map(\.digest) == ["one", DefaultBackground.digest]
            && !existingChoices.photos[1].inRotation
            && defaultOnlyProfile.photos.count == 1 && defaultOnlyProfile.photos[0].inRotation
            && reseededRemoved && removedDefault.photos.count == 1 && !removedDefault.photos[0].inRotation
            && duplicateDefault.photos.count == 1 && duplicateDefault.photos[0].uuid == DefaultBackground.uuid
    }
}
