import Foundation

/// The bundled fallback is a local media record so it appears in every TV profile
/// without copying the shipped JPEG into the user's library.
enum DefaultBackground {
    static let uuid = UUID(uuidString: "4ABE9B70-CB73-4DC6-8089-EBA4575E9C19")!
    static let digest = "4abe9b70cb73d6c40889eba4575e9c19c450877328b1003254a395b6e20e9cc6"
    static let displayName = "Fluorescent Anemone Reef Macro"
    static let fileName = "wallpaper.jpg"

    static var bundledURL: URL? {
        Bundle.main.url(forResource: "wallpaper", withExtension: "jpg")
    }

    static func isBundled(_ photo: LocalPhoto) -> Bool {
        photo.uuid == uuid || photo.digest == digest
    }

    static func photo(inRotation: Bool) -> LocalPhoto {
        LocalPhoto(
            uuid: uuid,
            digest: digest,
            name: displayName,
            fileName: fileName,
            addedAt: .distantPast,
            inRotation: inRotation
        )
    }

    /// Adds exactly one normalized default record. A new empty profile selects it;
    /// a profile with existing choices keeps it stored only. A disabled or missing
    /// persisted record returns as stored-only, never automatically selected.
    @discardableResult
    static func seed(into settings: inout LocalSettings) -> Bool {
        let matches = settings.photos.indices.filter { isBundled(settings.photos[$0]) }
        if matches.isEmpty {
            settings.photos.append(photo(inRotation: settings.defaultBackgroundEnabled && settings.photos.isEmpty))
            return true
        }

        let first = matches[0]
        let original = settings.photos[first]
        let normalized = photo(inRotation: original.inRotation && settings.defaultBackgroundEnabled)
        settings.photos[first] = normalized
        for index in matches.dropFirst().reversed() {
            settings.photos.remove(at: index)
        }
        return matches.count > 1 || original != normalized
    }

    static func selfTestsPass() -> Bool {
        let photo = LocalPhoto(uuid: UUID(), digest: "photo", name: "Photo", fileName: "photo.jpg", addedAt: .distantPast, inRotation: true)
        let video = LocalPhoto(uuid: UUID(), digest: "video", name: "Video", fileName: "video.mp4", addedAt: .distantPast, inRotation: false, kind: .video)
        var settings = LocalSettings(photos: [photo, video, DefaultBackground.photo(inRotation: true)])
        BackgroundSelection.set(photo, in: &settings)
        let replaced = settings.photos.filter(\.inRotation).map(\.digest) == ["photo"]
            && !settings.defaultBackgroundEnabled
            && settings.videoPlayback == .cycle
            && settings.loopVideoId == nil
            && settings.pendingBackgroundReplacement
        BackgroundSelection.set(video, in: &settings)
        let looped = settings.photos.filter(\.inRotation).map(\.digest) == ["video"]
            && settings.videoPlayback == .loop
            && settings.loopVideoId == "video"
            && !settings.photos.contains(where: { isBundled($0) && $0.inRotation })
        BackgroundSelection.remove(video, in: &settings)
        let removed = settings.photos.allSatisfy { !$0.inRotation }
            && !settings.defaultBackgroundEnabled
            && settings.videoPlayback == .cycle
            && settings.loopVideoId == nil
        let protectsLegacyRemoval = !BackgroundSyncPolicy.mayReplace(
            pendingBackgroundReplacement: true,
            hasActiveMedia: false,
            receiverDefaultBackgroundEnabled: nil
        )
        let permitsLegacySet = BackgroundSyncPolicy.mayReplace(
            pendingBackgroundReplacement: true,
            hasActiveMedia: true,
            receiverDefaultBackgroundEnabled: nil
        )
        return replaced && looped && removed && protectsLegacyRemoval && permitsLegacySet
    }
}

enum BackgroundSyncPolicy {
    /// An older receiver can accept a nonempty replacement, but it cannot safely
    /// interpret a blank rotation that explicitly disables the bundled fallback.
    static func mayReplace(
        pendingBackgroundReplacement: Bool,
        hasActiveMedia: Bool,
        receiverDefaultBackgroundEnabled: Bool?
    ) -> Bool {
        !pendingBackgroundReplacement || hasActiveMedia || receiverDefaultBackgroundEnabled != nil
    }
}

enum BackgroundSelection {
    static func set(_ photo: LocalPhoto, in settings: inout LocalSettings) {
        guard let selectedIndex = settings.photos.firstIndex(where: { $0.id == photo.id }) else { return }
        for index in settings.photos.indices {
            settings.photos[index].inRotation = index == selectedIndex
        }
        let selected = settings.photos[selectedIndex]
        if selected.mediaKind == .video {
            settings.videoPlayback = .loop
            settings.loopVideoId = selected.digest
        } else {
            settings.videoPlayback = .cycle
            settings.loopVideoId = nil
        }
        settings.defaultBackgroundEnabled = DefaultBackground.isBundled(selected)
        settings.pendingBackgroundReplacement = true
        settings.slideshowEnabled = true
    }

    static func remove(_ photo: LocalPhoto, in settings: inout LocalSettings) {
        guard let index = settings.photos.firstIndex(where: { $0.id == photo.id }) else { return }
        settings.photos[index].inRotation = false
        if settings.loopVideoId == photo.digest {
            settings.loopVideoId = nil
            settings.videoPlayback = .cycle
        }
        settings.defaultBackgroundEnabled = false
        settings.pendingBackgroundReplacement = true
        settings.slideshowEnabled = true
    }

    static func clearBundledSelection(in settings: inout LocalSettings) {
        for index in settings.photos.indices where DefaultBackground.isBundled(settings.photos[index]) {
            settings.photos[index].inRotation = false
        }
    }
}
