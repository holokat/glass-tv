import AppKit
import Foundation
import ImageIO

/// Offline, AI-generated destination artwork. These images never represent
/// current weather. Catalog entries are admitted only after their JPEG files
/// are found in the application bundle, so the UI cannot offer fake variants.
enum WeatherArtwork {
    private struct AvailableVariant {
        let index: Int
        let filename: String
    }

    private struct Catalog: Decodable {
        let version: Int
        let destinations: [CatalogDestination]
    }

    private struct CatalogDestination: Decodable {
        let key: String
        let names: [String]
        let geoIDs: [String]
        let latitude: Double
        let longitude: Double
        let radiusKM: Double
        let images: [String]
    }

    private struct Destination {
        let key: String
        let names: [String]
        let geoIDs: [String]
        let latitude: Double
        let longitude: Double
        let radiusKM: Double
        let variants: [AvailableVariant]

        func matches(_ location: WeatherLocation) -> Bool {
            guard location.latitude.isFinite, location.longitude.isFinite,
                  (-90...90).contains(location.latitude), (-180...180).contains(location.longitude) else { return false }
            let knownName = names.contains(WeatherArtwork.normalized(location.name))
            guard knownName || geoIDs.contains(location.id) else { return false }
            let radians = Double.pi / 180
            let dLat = (location.latitude - latitude) * radians
            let dLon = (location.longitude - longitude) * radians
            let a = pow(sin(dLat / 2), 2)
                + cos(latitude * radians) * cos(location.latitude * radians) * pow(sin(dLon / 2), 2)
            let km = 6_371 * 2 * atan2(sqrt(max(0, a)), sqrt(max(0, 1 - a)))
            return km <= radiusKM
        }
    }

    // Centers and IDs are verified with the Open-Meteo/GeoNames city-search
    // provider. Coordinate guards prevent homonymous cities from receiving
    // unrelated landmark artwork.
    private static let legacyDestinations: [Destination] = [
        .init(key: "tokyo", names: ["tokyo", "東京", "東京都"], geoIDs: ["geo-1850147"], latitude: 35.6895, longitude: 139.69171, radiusKM: 40, variants: [.init(index: 0, filename: "tokyo.png")]),
        .init(key: "denver", names: ["denver"], geoIDs: ["geo-5419384"], latitude: 39.73915, longitude: -104.9847, radiusKM: 35, variants: [.init(index: 0, filename: "denver.png")]),
        .init(key: "maui", names: ["maui"], geoIDs: ["geo-5850862"], latitude: 20.77024, longitude: -156.26816, radiusKM: 60, variants: [.init(index: 0, filename: "maui.png")]),
        .init(key: "reykjavik", names: ["reykjavik"], geoIDs: ["geo-3413829"], latitude: 64.13548, longitude: -21.89541, radiusKM: 30, variants: [.init(index: 0, filename: "reykjavik.png")]),
        .init(key: "singapore", names: ["singapore", "singapore island", "新加坡"], geoIDs: ["geo-1880252", "geo-1880251", "geo-1880249"], latitude: 1.28967, longitude: 103.85007, radiusKM: 40, variants: [.init(index: 0, filename: "singapore.png")]),
        .init(key: "paris", names: ["paris"], geoIDs: ["geo-2988507"], latitude: 48.85341, longitude: 2.3488, radiusKM: 30, variants: [.init(index: 0, filename: "paris.png")]),
        .init(key: "new-york", names: ["new york", "new york city", "nyc"], geoIDs: ["geo-5128581"], latitude: 40.71427, longitude: -74.00597, radiusKM: 35, variants: [.init(index: 0, filename: "new-york.png")]),
        .init(key: "london", names: ["london"], geoIDs: ["geo-2643743"], latitude: 51.50853, longitude: -0.12574, radiusKM: 40, variants: [.init(index: 0, filename: "london.png")])
    ]

    private static let destinations: [Destination] = {
        let catalogDestinations = loadCatalogDestinations()
        let catalogKeys = Set(catalogDestinations.map(\.key))
        let availableLegacy = legacyDestinations.compactMap { destination -> Destination? in
            guard !catalogKeys.contains(destination.key) else { return nil }
            let variants = destination.variants.filter { bundledImageExists($0.filename) }
            guard !variants.isEmpty else { return nil }
            return Destination(
                key: destination.key,
                names: destination.names,
                geoIDs: destination.geoIDs,
                latitude: destination.latitude,
                longitude: destination.longitude,
                radiusKM: destination.radiusKM,
                variants: variants
            )
        }
        return catalogDestinations + availableLegacy
    }()

    private static let cache: NSCache<NSString, NSImage> = {
        let cache = NSCache<NSString, NSImage>()
        cache.countLimit = 12
        cache.totalCostLimit = 96 * 1_024 * 1_024
        return cache
    }()

    static func key(for location: WeatherLocation) -> String? {
        destination(for: location)?.key
    }

    static func image(for location: WeatherLocation, variant: Int? = nil) -> NSImage? {
        guard let destination = destination(for: location),
              let imageName = imageName(for: destination, variant: variant ?? location.artworkVariant) else { return nil }
        let cacheKey = imageName as NSString
        if let image = cache.object(forKey: cacheKey) { return image }
        guard let image = loadBoundedImage(named: imageName) else { return nil }
        cache.setObject(image, forKey: cacheKey, cost: estimatedCost(for: image))
        return image
    }

    static func variantCount(for location: WeatherLocation) -> Int {
        destination(for: location)?.variants.count ?? 0
    }

    static func canCycleArtwork(for location: WeatherLocation) -> Bool {
        variantCount(for: location) >= 2
    }

    static func nextArtworkVariant(for location: WeatherLocation) -> Int? {
        guard let destination = destination(for: location) else { return nil }
        return nextVariant(current: location.artworkVariant, variants: destination.variants)
    }

    static func caption(for location: WeatherLocation) -> String {
        key(for: location) == nil
            ? "No destination artwork available"
            : "AI-generated destination artwork. Not a live weather image."
    }

    static func selfTestsPass() -> Bool {
        let legacyJSON = Data("{\"id\":\"geo-1850147\",\"name\":\"Tokyo\",\"country\":\"Japan\",\"latitude\":35.6895,\"longitude\":139.69171,\"enabled\":true}".utf8)
        guard let legacy = try? JSONDecoder().decode(WeatherLocation.self, from: legacyJSON), legacy.artworkVariant == 0 else { return false }

        let outOfBoundsJSON = Data("{\"id\":\"geo-1850147\",\"name\":\"Tokyo\",\"country\":\"Japan\",\"latitude\":35.6895,\"longitude\":139.69171,\"enabled\":true,\"artworkVariant\":99}".utf8)
        guard let clamped = try? JSONDecoder().decode(WeatherLocation.self, from: outOfBoundsJSON), clamped.artworkVariant == 4 else { return false }
        let negativeVariant = WeatherLocation(id: "geo-1850147", name: "Tokyo", country: "Japan", latitude: 35.6895, longitude: 139.69171, enabled: true, artworkVariant: -1)
        guard negativeVariant.artworkVariant == 0 else { return false }

        let catalogJSON = Data("{\"version\":1,\"destinations\":[{\"key\":\"tokyo\",\"names\":[\"Tokyo\"],\"geoIDs\":[\"geo-1850147\"],\"latitude\":35.6895,\"longitude\":139.69171,\"radiusKM\":40,\"images\":[\"tokyo-01.jpg\",\"tokyo-02.jpg\",\"missing.jpg\"]}]}".utf8)
        let destinations = catalogDestinations(from: catalogJSON, imageExists: { $0 != "missing.jpg" })
        guard let tokyo = destinations.first,
              tokyo.variants.map(\.index) == [0, 1],
              imageName(for: tokyo, variant: 1) == "tokyo-02.jpg" else { return false }
        let validLocation = WeatherLocation(id: "geo-1850147", name: "Tokyo", country: "Japan", latitude: 35.6895, longitude: 139.69171, enabled: false, artworkVariant: 1)
        let homonym = WeatherLocation(id: "geo-999", name: "Tokyo", country: "Elsewhere", latitude: 43.0, longitude: -79.0, enabled: true)
        guard tokyo.matches(validLocation), !tokyo.matches(homonym) else { return false }
        guard nextVariant(current: 1, variants: tokyo.variants) == 0,
              nextVariant(current: 4, variants: [.init(index: 0, filename: "only.jpg")]) == nil else { return false }

        let missingMiddleCatalog = Data("{\"version\":1,\"destinations\":[{\"key\":\"tokyo\",\"names\":[\"Tokyo\"],\"geoIDs\":[\"geo-1850147\"],\"latitude\":35.6895,\"longitude\":139.69171,\"radiusKM\":40,\"images\":[\"tokyo-01.jpg\",\"missing.jpg\",\"tokyo-03.jpg\"]}]}".utf8)
        guard let sparseTokyo = catalogDestinations(from: missingMiddleCatalog, imageExists: { $0 != "missing.jpg" }).first,
              sparseTokyo.variants.map(\.index) == [0, 2],
              imageName(for: sparseTokyo, variant: 2) == "tokyo-03.jpg",
              imageName(for: sparseTokyo, variant: 1) == nil,
              nextVariant(current: 0, variants: sparseTokyo.variants) == 2 else { return false }
        // Artwork preferences round-trip independently for each location.
        var first = validLocation
        first.artworkVariant = 3
        var second = homonym
        second.artworkVariant = 1
        guard let encoded = try? JSONEncoder().encode([first, second]),
              let restored = try? JSONDecoder().decode([WeatherLocation].self, from: encoded),
              restored.map(\.artworkVariant) == [3, 1],
              restored[0].enabled == first.enabled,
              restored[1].id == second.id else { return false }
        return catalogDestinations(from: catalogJSON, imageExists: { _ in false }).isEmpty
    }

    private static func destination(for location: WeatherLocation) -> Destination? {
        destinations.first { $0.matches(location) }
    }

    private static func imageName(for destination: Destination, variant: Int) -> String? {
        destination.variants.first { $0.index == WeatherLocation.normalizedArtworkVariant(variant) }?.filename
    }

    private static func nextVariant(current: Int, variants: [AvailableVariant]) -> Int? {
        guard variants.count >= 2 else { return nil }
        let selected = WeatherLocation.normalizedArtworkVariant(current)
        guard let currentIndex = variants.firstIndex(where: { $0.index == selected }) else {
            return variants.first?.index
        }
        return variants[(currentIndex + 1) % variants.count].index
    }

    private static func loadCatalogDestinations() -> [Destination] {
        guard let url = Bundle.main.url(forResource: "catalog", withExtension: "json", subdirectory: "weather-artwork"),
              let data = try? Data(contentsOf: url) else { return [] }
        return catalogDestinations(from: data, imageExists: bundledImageExists)
    }

    private static func catalogDestinations(
        from data: Data,
        imageExists: (String) -> Bool
    ) -> [Destination] {
        guard let catalog = try? JSONDecoder().decode(Catalog.self, from: data), catalog.version == 1 else { return [] }
        var keys = Set<String>()
        return catalog.destinations.compactMap { source in
            let names = Array(Set(source.names.map(normalized).filter { !$0.isEmpty })).sorted()
            let geoIDs = Array(Set(source.geoIDs.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })).sorted()
            guard !source.key.isEmpty, source.key.count <= 80,
                  source.key.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-")).contains($0) }),
                  keys.insert(source.key).inserted,
                  !names.isEmpty || !geoIDs.isEmpty,
                  source.latitude.isFinite, (-90...90).contains(source.latitude),
                  source.longitude.isFinite, (-180...180).contains(source.longitude),
                  source.radiusKM.isFinite, (0...500).contains(source.radiusKM) else { return nil }
            var filenames = Set<String>()
            let variants = source.images.enumerated().compactMap { index, imageName -> AvailableVariant? in
                guard index < 5, isSafeJPEGFilename(imageName), imageExists(imageName), filenames.insert(imageName).inserted else { return nil }
                return AvailableVariant(index: index, filename: imageName)
            }
            guard !variants.isEmpty else { return nil }
            return Destination(
                key: source.key,
                names: names,
                geoIDs: geoIDs,
                latitude: source.latitude,
                longitude: source.longitude,
                radiusKM: source.radiusKM,
                variants: variants
            )
        }
    }

    private static func bundledImageExists(_ imageName: String) -> Bool {
        bundledURL(for: imageName) != nil
    }

    private static func bundledURL(for imageName: String) -> URL? {
        let filename = (imageName as NSString).lastPathComponent
        let baseName = (filename as NSString).deletingPathExtension
        let extensionName = (filename as NSString).pathExtension.lowercased()
        guard filename == imageName, !baseName.isEmpty,
              ["jpg", "jpeg", "png"].contains(extensionName) else { return nil }
        return Bundle.main.url(
            forResource: baseName,
            withExtension: extensionName,
            subdirectory: "weather-artwork"
        )
    }

    private static func isSafeJPEGFilename(_ imageName: String) -> Bool {
        let filename = (imageName as NSString).lastPathComponent
        let baseName = (filename as NSString).deletingPathExtension
        return filename == imageName && !baseName.isEmpty
            && ["jpg", "jpeg"].contains((filename as NSString).pathExtension.lowercased())
    }

    private static func loadBoundedImage(named imageName: String) -> NSImage? {
        guard let url = bundledURL(for: imageName),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2_048,
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }

    private static func estimatedCost(for image: NSImage) -> Int {
        let pixels = image.representations.first.map { Int($0.pixelsWide) * Int($0.pixelsHigh) } ?? 0
        return max(pixels * 4, 1)
    }

    private static func normalized(_ name: String) -> String {
        name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
