import Foundation
import Combine
import OSLog
import Network

@MainActor
final class CompanionModel: ObservableObject {
    @Published private(set) var host = "" {
        didSet {
            guard oldValue != host, !isActivatingProfile else { return }
            _ = save()
            UserDefaults.standard.set(host, forKey: "glassTV.host")
            resetConnectionState(for: host)
        }
    }
    @Published private(set) var settings: LocalSettings
    @Published private(set) var savedTVs: [TVProfileDescriptor] = []
    @Published private(set) var serverStatus: ServerStatus?
    @Published private(set) var connectionMessage = "Not paired"
    @Published private(set) var progress: Double?
    @Published private(set) var isWorking = false
    @Published private(set) var connectionActivity: TVConnectionActivity = .idle
    @Published private(set) var lastStatusCheck: Date?
    @Published private(set) var isLocallyDisconnected = false
    @Published private(set) var isEditingConnectionAddress = false
    @Published private(set) var isApplicationActive = false
    @Published private(set) var weatherSyncMessage = ""
    @Published private(set) var appsSyncMessage = ""
    @Published private(set) var layoutSyncMessage = ""
    @Published private(set) var importMessage = ""
    @Published var discoveredPin: String?
    @Published var pairingCode = ""
    @Published var errorMessage: String?
    @Published var presentsErrorAlert = false

    private let library = LibraryStore()
    private let connectionLog = Logger(subsystem: "local.glasstv.companion", category: "connection")
    private var credential: PairCredential?
    private var weatherSyncTask: Task<Void, Never>?
    private var appsSyncTask: Task<Void, Never>?
    private var appsApplyTask: Task<Void, Never>?
    private var weatherChangesPending = false
    /// In-flight weather delivery may immediately retry only a newer local edit, never an
    /// unsupported receiver response or a persistent network failure.
    private var weatherEditGeneration = 0
    private var appsChangesPending = false
    private var automaticStatusFailures = 0
    private var nextAutomaticStatusCheck = Date.distantPast
    private let networkMonitor = NWPathMonitor()
    private let networkMonitorQueue = DispatchQueue(label: "local.glasstv.companion.network-path")
    private var hasObservedNetworkPath = false
    private var networkPathIsSatisfied = false
    private var networkPathInterfaces = Set<String>()
    private var profiles: TVProfiles
    private var activeProfileID: String?
    private var pendingTVName: String?
    private var isActivatingProfile = false

    init() {
        let legacySettings = library.load()
        let legacyHost = UserDefaults.standard.string(forKey: "glassTV.host") ?? ""
        let credentials = KeychainStore.allCredentials()
        var loadedProfiles = library.loadTVProfiles() ?? TVProfiles()
        // Keep existing legacy media available while profiles are introduced;
        // photo files remain shared and each profile owns only rotation/order.
        loadedProfiles.mergeSharedPhotos(legacySettings.photos)
        var uniqueIDs = Set<String>()
        loadedProfiles.profiles = loadedProfiles.profiles.filter { uniqueIDs.insert($0.id).inserted }
        if let activeID = loadedProfiles.activeID, !uniqueIDs.contains(activeID) {
            loadedProfiles.activeID = nil
            loadedProfiles.activeIsPending = loadedProfiles.pending != nil
        }
        var didMigrateCredentials = false
        var seenDeviceIDs = Set(loadedProfiles.profiles.map(\.id))
        var seenHosts = Set(loadedProfiles.profiles.map { $0.descriptor.host.lowercased() })
        for credential in credentials.sorted(by: {
            if $0.host.caseInsensitiveCompare(legacyHost) == .orderedSame { return true }
            if $1.host.caseInsensitiveCompare(legacyHost) == .orderedSame { return false }
            return $0.host.localizedStandardCompare($1.host) == .orderedAscending
        }) {
            guard seenDeviceIDs.insert(credential.deviceId).inserted,
                  seenHosts.insert(credential.host.lowercased()).inserted else { continue }
            let profileSettings = credential.host.caseInsensitiveCompare(legacyHost) == .orderedSame
                ? legacySettings
                : loadedProfiles.freshSettings()
            loadedProfiles.profiles.append(TVProfile(
                descriptor: TVProfileDescriptor(id: credential.deviceId, host: credential.host, name: nil, deviceName: nil, manufacturer: nil, model: nil),
                settings: profileSettings
            ))
            didMigrateCredentials = true
        }

        let selectedProfile = loadedProfiles.profiles.first(where: { $0.id == loadedProfiles.activeID })
            ?? loadedProfiles.profile(host: legacyHost)
        if loadedProfiles.activeIsPending, let pending = loadedProfiles.pending {
            settings = pending.settings
            host = pending.host
            pendingTVName = pending.name
            activeProfileID = nil
        } else if let selectedProfile {
            settings = loadedProfiles.settings(for: selectedProfile)
            host = selectedProfile.descriptor.host
            activeProfileID = selectedProfile.id
            loadedProfiles.activeID = selectedProfile.id
            loadedProfiles.activeIsPending = false
        } else {
            // An unpaired legacy address retains local work in a private pending
            // draft. It never appears in savedTVs before confirmation.
            let pending = PendingTVProfile(host: legacyHost, name: nil, settings: legacySettings)
            loadedProfiles.pending = pending
            loadedProfiles.activeID = nil
            loadedProfiles.activeIsPending = true
            loadedProfiles.mergeSharedPhotos(legacySettings.photos)
            settings = legacySettings
            host = legacyHost
            pendingTVName = nil
            activeProfileID = nil
            didMigrateCredentials = true
        }
        profiles = loadedProfiles
        savedTVs = loadedProfiles.profiles.map(\.descriptor)
        credential = KeychainStore.load(host: host)
        isLocallyDisconnected = UserDefaults.standard.bool(forKey: Self.manualDisconnectKey(for: host))
        if credential != nil { connectionMessage = "Paired. Refresh to check the TV." }
        if didMigrateCredentials { try? library.save(loadedProfiles, activeSettings: settings) }
        startNetworkMonitoring()
    }

    var photos: [LocalPhoto] { settings.photos }
    var activePhotos: [LocalPhoto] { settings.photos.filter(\.inRotation) }
    var videos: [LocalPhoto] { settings.photos.filter { $0.mediaKind == .video } }
    var isPaired: Bool { credential != nil }
    var tvConnectionState: TVConnectionState {
        .resolve(activity: connectionActivity, isPaired: isPaired,
                 hasCertificateChallenge: discoveredPin != nil, hasAuthenticatedStatus: serverStatus != nil)
    }
    var isConnected: Bool {
        TVConnectionActionPolicy.isConnected(
            state: tvConnectionState,
            isPaired: isPaired,
            hasAuthenticatedStatus: serverStatus != nil
        )
    }
    var canSync: Bool {
        TVConnectionActionPolicy.canSync(
            state: tvConnectionState,
            isPaired: isPaired,
            hasAuthenticatedStatus: serverStatus != nil,
            isBusy: isWorking,
            hasActiveMedia: !activePhotos.isEmpty
        )
    }
    var canAutoRefresh: Bool {
        TVConnectionActionPolicy.canAutoRefresh(
            isPaired: isPaired,
            isBusy: isWorking,
            hasPairingChallenge: discoveredPin != nil,
            isLocallyDisconnected: isLocallyDisconnected
        ) && !isEditingConnectionAddress
    }
    var automaticStatusRefreshDelay: TimeInterval {
        TVAutomaticReconnectPolicy.refreshDelay(afterFailures: automaticStatusFailures)
    }
    var interval: Int { settings.intervalSeconds }
    var shuffle: Bool { settings.shuffle }
    var videoPlayback: VideoPlayback { settings.videoPlayback }
    var loopVideoId: String? { settings.loopVideoId }
    var muteVideos: Bool { settings.muteVideos }
    var slideshowEnabled: Bool { settings.slideshowEnabled }
    var fitMode: FitMode { settings.fitMode }
    var showTVInterface: Bool { settings.showTVInterface }
    var weather: WeatherSettings { settings.weather }
    var hasConfiguredWeather: Bool { settings.hasConfiguredWeather }
    var hasPendingWeatherSync: Bool { settings.pendingWeatherSync }
    var apps: AppsSettings { settings.apps }
    var hasConfiguredApps: Bool { settings.hasConfiguredApps }
    var hasPendingAppsSync: Bool { settings.pendingAppsSync }
    var selectedInputId: String? { settings.selectedInputId }
    var layoutDraft: LayoutSettings { settings.layoutDraft }
    var appliedLayout: LayoutSettings { settings.appliedLayout }
    var hasConfiguredLayout: Bool { settings.hasConfiguredLayout }
    var layoutHasChanges: Bool {
        let positionsChanged = !(settings.layoutDraft.wireObject() as NSDictionary).isEqual(to: settings.appliedLayout.wireObject())
        return positionsChanged
            || (settings.layoutDraft.weatherStyleExplicitlySet && settings.layoutDraft.weatherStyle != settings.appliedLayout.weatherStyle)
            || settings.layoutDraft.shortcutStyleExplicitlySet
    }

    /// Internal capability factory for the movie subsystem. Credentials remain private to this model.
    func movieTVConnection() -> MovieTVConnection? {
        guard let credential else { return nil }
        return MovieTVConnection(host: host, certificatePin: credential.certificatePin,
                                 client: GlassTVClient(host: host, pin: credential.certificatePin, token: credential.token))
    }

    var activeTVName: String {
        savedTVs.first(where: { $0.id == activeProfileID })?.displayName ?? pendingTVName ?? host
    }
    var isSwitchingBlocked: Bool { isWorking }

    func isActiveTV(host: String) -> Bool {
        self.host.caseInsensitiveCompare(host.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
    }

    /// Selects only a receiver that has been confirmed by pairing or status.
    @discardableResult
    func selectTV(host requestedHost: String) -> Bool {
        guard !isWorking,
              let requested = profiles.profile(host: requestedHost.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        if requested.id == activeProfileID, !profiles.activeIsPending { return true }
        guard save() else { return false }
        var candidate = profiles
        guard let profile = candidate.profiles.first(where: { $0.id == requested.id }) else { return false }
        let nextSettings = candidate.settings(for: profile)
        candidate.activeID = profile.id
        candidate.activeIsPending = false
        guard writeProfiles(candidate, activeSettings: nextSettings) else { return false }
        activate(profile, settings: nextSettings)
        return true
    }

    /// Starts a fresh pairing target. It is deliberately not inserted into
    /// `savedTVs` until pairing or authenticated status confirms a receiver.
    @discardableResult
    func addTV(host requestedHost: String, name: String? = nil) -> Bool {
        let address = requestedHost.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !isWorking, Self.isValidIPv4(address) else { return false }
        // A user choosing “Connect another TV” may be replacing a different
        // receiver that inherited this DHCP address. Begin a fresh draft.
        guard save() else { return false }
        let freshSettings = profiles.freshSettings()
        var candidate = profiles
        candidate.activeID = nil
        candidate.activeIsPending = true
        candidate.pending = PendingTVProfile(host: address, name: Self.normalizedName(name), settings: freshSettings)
        candidate.mergeSharedPhotos(freshSettings.photos)
        guard writeProfiles(candidate, activeSettings: freshSettings) else { return false }
        cancelProfileBackgroundWork()
        activeProfileID = nil
        settings = freshSettings
        pendingTVName = Self.normalizedName(name)
        isActivatingProfile = true
        host = address
        isActivatingProfile = false
        UserDefaults.standard.set(address, forKey: "glassTV.host")
        resetConnectionState(for: address)
        return true
    }

    func renameActiveTV(_ name: String?) {
        guard !isWorking, let id = activeProfileID else { return }
        var candidate = profiles
        guard let index = candidate.profiles.firstIndex(where: { $0.id == id }) else { return }
        candidate.profiles[index].descriptor.name = Self.normalizedName(name)
        _ = writeProfiles(candidate, activeSettings: settings)
    }

    @discardableResult
    private func save() -> Bool {
        var candidate = profiles
        if let activeProfileID {
            candidate.save(settings, for: activeProfileID)
            candidate.activeID = activeProfileID
            candidate.activeIsPending = false
        } else {
            candidate.activeID = nil
            candidate.activeIsPending = true
            candidate.pending = PendingTVProfile(host: host, name: pendingTVName, settings: settings)
            candidate.mergeSharedPhotos(settings.photos)
        }
        return writeProfiles(candidate, activeSettings: settings)
    }

    @discardableResult
    private func writeProfiles(_ candidate: TVProfiles, activeSettings: LocalSettings) -> Bool {
        do {
            try library.save(candidate, activeSettings: activeSettings)
            profiles = candidate
            savedTVs = candidate.profiles.map(\.descriptor)
            return true
        } catch {
            errorMessage = "Could not save the local library: \(error.localizedDescription)"
            return false
        }
    }

    @discardableResult
    private func persistProfiles() -> Bool {
        writeProfiles(profiles, activeSettings: settings)
    }

    private func activate(_ profile: TVProfile, settings nextSettings: LocalSettings) {
        cancelProfileBackgroundWork()
        activeProfileID = profile.id
        settings = nextSettings
        pendingTVName = nil
        isActivatingProfile = true
        host = profile.descriptor.host
        isActivatingProfile = false
        UserDefaults.standard.set(host, forKey: "glassTV.host")
        resetConnectionState(for: host)
    }

    private func promoteCurrentTV(deviceID: String, deviceName: String? = nil, manufacturer: String? = nil, model: String? = nil) {
        var candidate = profiles
        func updateIdentity(at index: Int) {
            candidate.profiles[index].descriptor.host = host
            if let deviceName = Self.normalizedName(deviceName) { candidate.profiles[index].descriptor.deviceName = deviceName }
            if let manufacturer = Self.normalizedName(manufacturer) { candidate.profiles[index].descriptor.manufacturer = manufacturer }
            if let model = Self.normalizedName(model) { candidate.profiles[index].descriptor.model = model }
        }

        if let id = activeProfileID,
           let index = candidate.profiles.firstIndex(where: { $0.id == id }),
           id == deviceID {
            updateIdentity(at: index)
        } else if let existing = candidate.profileIndex(deviceID: deviceID) {
            activeProfileID = candidate.profiles[existing].id
            updateIdentity(at: existing)
            // A receiver alias must restore the existing profile rather than
            // replacing its layout, weather, Apps, or rotation with fresh defaults.
            settings = candidate.settings(for: candidate.profiles[existing])
        } else {
            // A re-pair at a reused address identified a different physical TV.
            // Preserve prior profile settings, but retain a first pairing's local
            // pending draft so its offline work is not thrown away.
            if activeProfileID != nil {
                settings = candidate.freshSettings()
            } else if let pending = candidate.pending {
                settings = pending.settings
            }
            candidate.profiles.append(TVProfile(
                descriptor: TVProfileDescriptor(id: deviceID, host: host, name: pendingTVName, deviceName: Self.normalizedName(deviceName), manufacturer: Self.normalizedName(manufacturer), model: Self.normalizedName(model)),
                settings: settings
            ))
            activeProfileID = deviceID
        }
        candidate.activeID = activeProfileID
        candidate.activeIsPending = false
        candidate.pending = nil
        candidate.mergeSharedPhotos(settings.photos)
        pendingTVName = nil
        _ = writeProfiles(candidate, activeSettings: settings)
    }

    private func cancelProfileBackgroundWork() {
        weatherSyncTask?.cancel(); weatherSyncTask = nil
        appsSyncTask?.cancel(); appsSyncTask = nil
        appsApplyTask?.cancel(); appsApplyTask = nil
        weatherChangesPending = false
        appsChangesPending = false
        weatherSyncMessage = ""
        appsSyncMessage = ""
        layoutSyncMessage = ""
        automaticStatusFailures = 0
        nextAutomaticStatusCheck = .distantPast
    }

    private func resetConnectionState(for address: String) {
        AppDiagnostics.shared.record(.switched)
        credential = KeychainStore.load(host: address)
        discoveredPin = nil
        pairingCode = ""
        serverStatus = nil
        lastStatusCheck = nil
        progress = nil
        connectionActivity = .idle
        isLocallyDisconnected = UserDefaults.standard.bool(forKey: Self.manualDisconnectKey(for: address))
        connectionMessage = credential == nil ? "Not paired" : "Paired. Refresh to check the TV."
        cancelProfileBackgroundWork()
    }

    private static func normalizedName(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return String(value.prefix(80))
    }

    private static func isValidIPv4(_ value: String) -> Bool {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy { Int($0).map { (0...255).contains($0) } ?? false }
    }

    private static func manualDisconnectKey(for host: String) -> String {
        "glassTV.manualDisconnect.\(host)"
    }

    private func setLocallyDisconnected(_ value: Bool) {
        isLocallyDisconnected = value
        UserDefaults.standard.set(value, forKey: Self.manualDisconnectKey(for: host))
    }

    func setConnectionAddressEditing(_ value: Bool) {
        isEditingConnectionAddress = value
    }

    func setApplicationActive(_ value: Bool) {
        isApplicationActive = value
    }

    /// Recheck a saved pairing after this Mac becomes active, wakes, or regains
    /// a usable network path. This only reads authenticated status; receiver
    /// changes remain limited to existing automatic Apps reconciliation and an
    /// explicit Layout update.
    func resumeAutomaticConnection() async {
        guard isApplicationActive,
              isPaired,
              !isLocallyDisconnected,
              discoveredPin == nil,
              !isEditingConnectionAddress else { return }
        nextAutomaticStatusCheck = .distantPast
        await refreshStatus()
    }

    private func startNetworkMonitoring() {
        networkMonitor.pathUpdateHandler = { [weak self] path in
            let isSatisfied = path.status == .satisfied
            let interfaces = Self.activeInterfaceNames(for: path)
            Task { @MainActor [weak self] in
                self?.networkPathDidChange(isSatisfied: isSatisfied, interfaces: interfaces)
            }
        }
        networkMonitor.start(queue: networkMonitorQueue)
    }

    private nonisolated static func activeInterfaceNames(for path: NWPath) -> Set<String> {
        let types: [NWInterface.InterfaceType] = [.wifi, .wiredEthernet, .cellular, .loopback, .other]
        return Set(types.compactMap { path.usesInterfaceType($0) ? String(describing: $0) : nil })
    }

    private func networkPathDidChange(isSatisfied: Bool, interfaces: Set<String>) {
        defer {
            hasObservedNetworkPath = true
            networkPathIsSatisfied = isSatisfied
            networkPathInterfaces = interfaces
        }
        guard TVNetworkRecoveryPolicy.shouldRequestReconnect(
            hasObservedPath: hasObservedNetworkPath,
            wasSatisfied: networkPathIsSatisfied,
            previousInterfaces: networkPathInterfaces,
            isSatisfied: isSatisfied,
            interfaces: interfaces
        ), isPaired,
              !isLocallyDisconnected,
              discoveredPin == nil,
              !isEditingConnectionAddress else { return }
        nextAutomaticStatusCheck = .distantPast
        guard isApplicationActive else { return }
        Task { [weak self] in await self?.resumeAutomaticConnection() }
    }

    func setInterval(_ value: Int) { settings.intervalSeconds = value; save() }
    func setShuffle(_ value: Bool) { settings.shuffle = value; save() }
    func setInput(_ input: String?) {
        settings.selectedInputId = input
        settings.hasConfiguredInput = true
        settings.pendingInputSync = true
        save()
        queueAppsSync()
    }
    func updateApps(_ apps: AppsSettings) {
        settings.apps = apps
        settings.hasConfiguredApps = true
        settings.pendingAppsSync = true
        save()
        queueAppsSync()
    }

    func updateLayoutDraft(_ layout: LayoutSettings) {
        guard layout != settings.layoutDraft else { return }
        settings.layoutDraft = layout
        settings.hasConfiguredLayout = true
        layoutSyncMessage = isConnected
            ? (layoutHasChanges ? "Layout changes ready to update TV" : "Layout is up to date")
            : "Saved locally. Connect to update TV."
        save()
    }

    func resetLayoutPositions() {
        var draft = settings.layoutDraft
        draft.resetPositions()
        updateLayoutDraft(draft)
    }

    /// Layout is intentionally explicit: drafts persist offline and do not change the
    /// receiver until the user selects Update TV.
    func applyLayout() {
        guard isConnected, let credential, !isWorking else {
            layoutSyncMessage = isConnected ? "TV is busy" : "Connect to update TV."
            return
        }
        guard layoutHasChanges else {
            layoutSyncMessage = "Layout is up to date"
            return
        }
        let draft = settings.layoutDraft
        // Layout style is deliberately staged. Merge it into the freshly-read
        // receiver Apps section in submit(), never into an old local snapshot.
        let appsBeforeSubmission = settings.apps
        let hasDraftShortcutStyle = draft.shortcutStyleExplicitlySet
        let hasPendingAppsAtSubmission = settings.pendingAppsSync
        let pendingAppsAtSubmission = hasPendingAppsAtSubmission ? settings.apps : nil
        let address = host
        let fallbackShowTVInterface = settings.showTVInterface
        let localWeatherStyleAtSubmission = settings.weather.style
        isWorking = true
        layoutSyncMessage = "Updating TV layout…"
        let client = GlassTVClient(host: address, pin: credential.certificatePin, token: credential.token)
        Task { [weak self] in
            guard let self else { return }
            defer { self.isWorking = false }
            do {
                var status = try await client.status()
                guard self.isCurrentLayoutConnection(address: address, credential: credential) else { return }
                func submit(_ current: ServerStatus) async throws -> AppsSettings? {
                    var weather = current.playlist.weather
                    if var copiedWeather = weather {
                        copiedWeather.style = draft.weatherStyleExplicitlySet ? draft.weatherStyle : copiedWeather.style
                        copiedWeather.normalize()
                        weather = copiedWeather
                    }
                    var apps = pendingAppsAtSubmission ?? current.playlist.apps
                    if hasDraftShortcutStyle {
                        var updated = apps ?? AppsSettings()
                        updated.shortcutStyle = draft.shortcutStyle
                        apps = updated
                    }
                    _ = try await client.putPlaylist(
                        baseRevision: current.revision,
                        intervalSeconds: current.playlist.intervalSeconds,
                        shuffle: current.playlist.shuffle,
                        images: current.playlist.images,
                        selectedInputId: current.selectedInputId,
                        videoPlayback: current.playlist.videoPlayback ?? .cycle,
                        loopVideoId: current.playlist.loopVideoId,
                        muteVideos: current.playlist.muteVideos ?? true,
                        slideshowEnabled: current.playlist.slideshowEnabled ?? true,
                        fitMode: current.playlist.fitMode ?? .fill,
                        showTVInterface: current.playlist.showTVInterface ?? fallbackShowTVInterface,
                        weather: weather,
                        apps: apps,
                        layout: draft
                    )
                    return apps
                }
                var submittedApps: AppsSettings? = nil
                do { submittedApps = try await submit(status) }
                catch let error as GlassTVClientError {
                    guard case .unexpectedResponse(409, _) = error else { throw error }
                    status = try await client.status()
                    guard self.isCurrentLayoutConnection(address: address, credential: credential) else { return }
                    submittedApps = try await submit(status)
                }
                let readback = try await client.status()
                guard self.isCurrentLayoutConnection(address: address, credential: credential) else { return }
                guard let readbackLayout = readback.playlist.layout,
                      (readbackLayout.wireObject() as NSDictionary).isEqual(to: draft.wireObject()) else {
                    layoutSyncMessage = "Update the TV app to use layout controls."
                    return
                }
                if let submittedApps, readback.playlist.apps != submittedApps {
                    layoutSyncMessage = "Update the TV app to use shortcut styles."
                    return
                }
                if draft.weatherStyleExplicitlySet,
                   readback.playlist.weather?.style != draft.weatherStyle {
                    layoutSyncMessage = "Update the TV app to use weather layout controls."
                    return
                }
                var applied = readbackLayout
                applied.weatherStyle = readback.playlist.weather?.style ?? settings.weather.style
                applied.weatherStyleExplicitlySet = false
                applied.shortcutStyle = submittedApps?.shortcutStyle ?? settings.apps.shortcutStyle
                applied.shortcutStyleExplicitlySet = false
                // A newer Apps edit wins over this staged layout choice. In that
                // case reconciliation below schedules the newer autosync rather
                // than reverting it to the value submitted with this layout.
                if hasDraftShortcutStyle, let submittedApps, settings.apps == appsBeforeSubmission {
                    settings.apps = submittedApps
                    settings.hasConfiguredApps = true
                    if !hasPendingAppsAtSubmission { settings.pendingAppsSync = false }
                }
                settings.appliedLayout = applied
                // Never discard a newer drag or a Weather-page edit made while this
                // request was in flight. Only update the submitted draft's local style.
                if settings.layoutDraft == draft { settings.layoutDraft = applied }
                if settings.weather.style == localWeatherStyleAtSubmission {
                    settings.weather.style = applied.weatherStyle
                }
                save()
                serverStatus = readback
                lastStatusCheck = Date()
                reconcileApps(afterAuthenticatedStatus: readback, address: address, credential: credential)
                layoutSyncMessage = "Layout updated on TV"
            } catch {
                guard self.isCurrentLayoutConnection(address: address, credential: credential) else { return }
                recordConnectionFailure(error, operation: "layout update")
                layoutSyncMessage = "Layout not updated: \(connectionFailureMessage(for: error))"
            }
        }
    }

    private func isCurrentLayoutConnection(address: String, credential: PairCredential) -> Bool {
        host == address && self.credential?.token == credential.token && self.credential?.certificatePin == credential.certificatePin && !isLocallyDisconnected
    }

    func applyApps() {
        appsSyncTask?.cancel()
        appsSyncTask = nil
        guard isConnected, let credential else {
            appsChangesPending = true
            appsSyncMessage = isPaired
                ? "Saved locally. Shortcut changes will sync when the TV reconnects."
                : "Saved locally. Pair with the TV to sync shortcuts."
            return
        }
        guard !isWorking else {
            appsChangesPending = true
            appsSyncMessage = "Shortcut changes are queued."
            if appsApplyTask == nil { waitForAppsSyncAfterCurrentWork() }
            return
        }
        appsChangesPending = false
        let appsBeingApplied = settings.apps
        let inputBeingApplied = settings.selectedInputId
        let inputWasConfigured = settings.hasConfiguredInput
        let fallbackShowTVInterface = settings.showTVInterface
        let address = host
        isWorking = true
        appsSyncMessage = "Syncing shortcuts to TV…"
        let client = GlassTVClient(host: address, pin: credential.certificatePin, token: credential.token)
        appsApplyTask = Task { [weak self] in
            guard let self else { return }
            var acceptedApps = false
            defer {
                self.isWorking = false
                self.appsApplyTask = nil
                let changedDuringApply = self.appsSyncWorkChanged(
                    apps: appsBeingApplied,
                    selectedInputId: inputBeingApplied,
                    inputWasConfigured: inputWasConfigured
                )
                if AppsSyncScheduling.shouldScheduleFollowUp(
                    acceptedApps: acceptedApps,
                    changedDuringApply: changedDuringApply
                ), self.isCurrentAppsConnection(address: address, credential: credential), self.isConnected {
                    self.scheduleAppsSync(after: 300_000_000)
                }
            }
            do {
                // Start from a fresh authenticated status. Apps must not replace media,
                // weather, or an input selection unless this Mac explicitly changed it.
                var status = try await client.status()
                guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }

                func submit(_ current: ServerStatus) async throws {
                    _ = try await client.putPlaylist(
                        baseRevision: current.revision,
                        intervalSeconds: current.playlist.intervalSeconds,
                        shuffle: current.playlist.shuffle,
                        images: current.playlist.images,
                        selectedInputId: inputWasConfigured ? inputBeingApplied : current.selectedInputId,
                        videoPlayback: current.playlist.videoPlayback ?? .cycle,
                        loopVideoId: current.playlist.loopVideoId,
                        muteVideos: current.playlist.muteVideos ?? true,
                        slideshowEnabled: current.playlist.slideshowEnabled ?? true,
                        fitMode: current.playlist.fitMode ?? .fill,
                        showTVInterface: current.playlist.showTVInterface ?? fallbackShowTVInterface,
                        weather: current.playlist.weather,
                        apps: appsBeingApplied,
                        layout: current.playlist.layout
                    )
                }

                do {
                    try await submit(status)
                } catch let error as GlassTVClientError {
                    guard case .unexpectedResponse(409, _) = error else { throw error }
                    status = try await client.status()
                    guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }
                    try await submit(status)
                }
                let updatedStatus = try await client.status()
                guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }
                serverStatus = updatedStatus
                lastStatusCheck = Date()
                guard updatedStatus.playlist.apps == appsBeingApplied else {
                    appsChangesPending = true
                    appsSyncMessage = "Update the TV app to sync launcher shortcuts."
                    return
                }
                acceptedApps = true
                let changedDuringApply = appsSyncWorkChanged(
                    apps: appsBeingApplied,
                    selectedInputId: inputBeingApplied,
                    inputWasConfigured: inputWasConfigured
                )
                if appsChangesPending && changedDuringApply {
                    appsSyncMessage = "New shortcut changes are queued."
                } else {
                    if settings.apps == appsBeingApplied { settings.pendingAppsSync = false }
                    if settings.selectedInputId == inputBeingApplied,
                       settings.hasConfiguredInput == inputWasConfigured { settings.pendingInputSync = false }
                    save()
                    appsChangesPending = false
                    appsSyncTask?.cancel()
                    appsSyncTask = nil
                    appsSyncMessage = "Shortcuts synced to TV"
                }
            } catch {
                guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }
                recordConnectionFailure(error, operation: "apps sync")
                appsChangesPending = true
                let failure = connectionFailureMessage(for: error)
                let changedDuringApply = appsSyncWorkChanged(
                    apps: appsBeingApplied,
                    selectedInputId: inputBeingApplied,
                    inputWasConfigured: inputWasConfigured
                )
                appsSyncMessage = appsChangesPending && changedDuringApply
                    ? "New shortcut changes are queued; earlier sync failed: \(failure)"
                    : "Shortcuts not synced: \(failure)"
            }
        }
    }

    private func queueAppsSync() {
        appsChangesPending = true
        appsSyncMessage = isConnected
            ? "Shortcut changes will sync to TV…"
            : (isPaired ? "Saved locally. Shortcut changes will sync when the TV reconnects." : "Saved locally. Pair with the TV to sync shortcuts.")
        requestAppsSync()
    }

    private func requestAppsSync() {
        guard isConnected else { return }
        guard appsApplyTask == nil else { return }
        if AppsSyncScheduling.shouldWaitForOtherWork(isWorking: isWorking, isApplyingApps: appsApplyTask != nil) {
            waitForAppsSyncAfterCurrentWork()
        } else {
            scheduleAppsSync(after: 650_000_000)
        }
    }

    private func requestAppsSyncAfterAuthenticatedStatus(address: String, credential: PairCredential) {
        appsSyncTask?.cancel()
        appsSyncTask = Task { [weak self] in
            while let self, (self.isWorking || self.connectionActivity != .idle) && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            guard let self,
                  !Task.isCancelled,
                  self.isCurrentAppsConnection(address: address, credential: credential),
                  self.isConnected,
                  let status = self.serverStatus,
                  self.appsNeedSync(with: status) else { return }
            self.scheduleAppsSync(after: 650_000_000)
        }
    }

    private func scheduleAppsSync(after delay: UInt64) {
        appsSyncTask?.cancel()
        appsSyncTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            self?.applyApps()
        }
    }

    private func waitForAppsSyncAfterCurrentWork() {
        appsSyncTask?.cancel()
        appsSyncTask = Task { [weak self] in
            while let self, self.isWorking && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            guard !Task.isCancelled else { return }
            self?.applyApps()
        }
    }

    private func isCurrentAppsConnection(address: String, credential: PairCredential) -> Bool {
        host == address
            && self.credential?.token == credential.token
            && self.credential?.certificatePin == credential.certificatePin
            && !isLocallyDisconnected
    }

    private func appsSyncWorkChanged(apps: AppsSettings, selectedInputId: String?, inputWasConfigured: Bool) -> Bool {
        settings.apps != apps
            || settings.selectedInputId != selectedInputId
            || settings.hasConfiguredInput != inputWasConfigured
    }

    private func appsNeedSync(with status: ServerStatus) -> Bool {
        let shortcutMismatch = settings.pendingAppsSync && status.playlist.apps != settings.apps
        let inputMismatch = settings.pendingInputSync
            && status.selectedInputId != settings.selectedInputId
        return shortcutMismatch || inputMismatch
    }

    private func reconcileApps(afterAuthenticatedStatus status: ServerStatus, address: String, credential: PairCredential) {
        guard settings.pendingAppsSync || settings.pendingInputSync else { return }
        guard status.playlist.apps != nil || !settings.pendingAppsSync else {
            appsChangesPending = true
            appsSyncMessage = "Update the TV app to sync launcher shortcuts."
            return
        }
        guard appsNeedSync(with: status) else {
            if settings.pendingAppsSync, status.playlist.apps == settings.apps {
                settings.pendingAppsSync = false
            }
            if settings.pendingInputSync, status.selectedInputId == settings.selectedInputId {
                settings.pendingInputSync = false
            }
            save()
            appsChangesPending = false
            appsSyncTask?.cancel()
            appsSyncTask = nil
            if !appsSyncMessage.isEmpty { appsSyncMessage = "Shortcuts synced to TV" }
            return
        }
        appsChangesPending = true
        appsSyncMessage = "Shortcut changes will sync to TV…"
        requestAppsSyncAfterAuthenticatedStatus(address: address, credential: credential)
    }

    private func reconcileWeather(afterAuthenticatedStatus status: ServerStatus, address: String, credential: PairCredential) {
        guard settings.pendingWeatherSync else { return }
        guard let remoteWeather = status.playlist.weather else {
            weatherChangesPending = true
            weatherSyncMessage = "Update the TV app to sync weather"
            return
        }
        switch SettingsSyncPolicy.action(pendingLocalEdit: true, local: settings.weather, remote: remoteWeather) {
        case .acknowledgeLocal:
            settings.pendingWeatherSync = false
            weatherChangesPending = false
            save()
            weatherSyncMessage = "Weather applied to TV"
        case .deliverLocal:
            weatherChangesPending = true
            weatherSyncMessage = "Weather changes pending"
            applyWeather()
        case .adoptRemote:
            break
        }
    }
    func setVideoPlayback(_ value: VideoPlayback) {
        if value == .loop, let firstVideo = videos.first(where: \.inRotation) {
            settings.videoPlayback = .loop
            if settings.loopVideoId == nil { settings.loopVideoId = firstVideo.digest }
        } else {
            settings.videoPlayback = .cycle
            settings.loopVideoId = nil
        }
        save()
    }
    func setLoopVideo(_ id: String?) { settings.loopVideoId = id; settings.videoPlayback = id == nil ? .cycle : .loop; save() }
    func setMuteVideos(_ value: Bool) { settings.muteVideos = value; save() }
    func setSlideshowEnabled(_ value: Bool) { settings.slideshowEnabled = value; save() }
    func setFitMode(_ value: FitMode) { settings.fitMode = value; save() }
    func setShowTVInterface(_ value: Bool) {
        settings.showTVInterface = value
        settings.hasConfiguredShowTVInterface = true
        save()
    }
    func updateWeather(_ weather: WeatherSettings) {
        var normalized = weather
        normalized.normalize()
        settings.weather = normalized
        settings.layoutDraft.weatherStyle = normalized.style
        settings.layoutDraft.weatherStyleExplicitlySet = false
        settings.hasConfiguredWeather = true
        settings.pendingWeatherSync = true
        weatherEditGeneration += 1
        save()
        weatherChangesPending = true
        weatherSyncMessage = "Weather changes pending"
        weatherSyncTask?.cancel()
        weatherSyncTask = Task {
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            applyWeather()
        }
    }

    func applyWeather() {
        guard isConnected, let credential else {
            weatherChangesPending = false
            weatherSyncMessage = isPaired ? "Connect to the TV to apply weather" : "Pair with the TV to apply weather"
            return
        }
        guard !isWorking else {
            weatherSyncMessage = "Weather changes pending"
            weatherSyncTask?.cancel()
            weatherSyncTask = Task {
                while self.isWorking && !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 250_000_000)
                }
                guard !Task.isCancelled else { return }
                self.applyWeather()
            }
            return
        }
        isWorking = true
        weatherSyncMessage = "Applying weather to TV…"
        let weatherBeingApplied = settings.weather
        let weatherGeneration = weatherEditGeneration
        let address = host
        let fallbackShowTVInterface = settings.showTVInterface
        let client = GlassTVClient(host: host, pin: credential.certificatePin, token: credential.token)
        Task {
            do {
                var status = try await client.status()
                guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }
                func submit(_ current: ServerStatus) async throws {
                    _ = try await client.putPlaylist(
                        baseRevision: current.revision,
                        intervalSeconds: current.playlist.intervalSeconds,
                        shuffle: current.playlist.shuffle,
                        images: current.playlist.images,
                        selectedInputId: current.selectedInputId,
                        videoPlayback: current.playlist.videoPlayback ?? .cycle,
                        loopVideoId: current.playlist.loopVideoId,
                        muteVideos: current.playlist.muteVideos ?? true,
                        slideshowEnabled: current.playlist.slideshowEnabled ?? true,
                        fitMode: current.playlist.fitMode ?? .fill,
                        showTVInterface: current.playlist.showTVInterface ?? fallbackShowTVInterface,
                        weather: weatherBeingApplied,
                        apps: current.playlist.apps,
                        layout: current.playlist.layout
                    )
                }
                do { try await submit(status) }
                catch let error as GlassTVClientError {
                    guard case .unexpectedResponse(409, _) = error else { throw error }
                    status = try await client.status()
                    guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }
                    try await submit(status)
                }
                let updatedStatus = try await client.status()
                guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }
                serverStatus = updatedStatus
                lastStatusCheck = Date()
                if updatedStatus.playlist.weather == weatherBeingApplied {
                    if settings.weather == weatherBeingApplied {
                        settings.pendingWeatherSync = false
                        save()
                    }
                    weatherChangesPending = settings.pendingWeatherSync
                    weatherSyncMessage = weatherChangesPending ? "Weather changes pending" : "Weather applied to TV"
                } else {
                    weatherChangesPending = true
                    weatherSyncMessage = "Update the TV app to sync weather"
                }
            } catch {
                guard !Task.isCancelled, self.isCurrentAppsConnection(address: address, credential: credential) else { return }
                recordConnectionFailure(error, operation: "weather sync")
                weatherChangesPending = true
                weatherSyncMessage = "Weather not applied: \(connectionFailureMessage(for: error))"
            }
            let retryNewerWeather = weatherChangesPending && weatherEditGeneration != weatherGeneration
            weatherChangesPending = retryNewerWeather
            isWorking = false
            if retryNewerWeather {
                weatherSyncMessage = "Weather changes pending"
                applyWeather()
            }
        }
    }
    func toggleRotation(_ photo: LocalPhoto) {
        guard let index = settings.photos.firstIndex(of: photo) else { return }
        settings.photos[index].inRotation.toggle()
        if settings.photos[index].inRotation == false, settings.loopVideoId == photo.digest { settings.loopVideoId = nil; settings.videoPlayback = .cycle }
        save()
    }
    func move(_ photo: LocalPhoto, offset: Int) {
        guard let index = settings.photos.firstIndex(of: photo) else { return }
        let target = index + offset
        guard settings.photos.indices.contains(target) else { return }
        settings.photos.swapAt(index, target); save()
    }

    func importFiles(_ urls: [URL]) {
        guard !urls.isEmpty, !isWorking else { return }
        isWorking = true
        importMessage = "Preparing media…"
        Task { [weak self] in
            guard let self else { return }
            var failures: [String] = []
            var imported = 0
            for url in urls {
                let accessedSecurityScope = url.startAccessingSecurityScopedResource()
                defer { if accessedSecurityScope { url.stopAccessingSecurityScopedResource() } }
                do {
                    let photo: LocalPhoto
                    var cacheUse: ConversionCacheUseToken?
                    defer {
                        if let cacheUse { Task { await ConversionCache.shared.releaseUse(cacheUse) } }
                    }
                    if ImageProcessor.isImageFile(url) {
                        photo = try ImageProcessor.importImage(from: url)
                    } else {
                        let result = try await VideoProcessor.importVideo(from: url) { [weak self] status in
                            Task { @MainActor in self?.importMessage = Self.importStatusText(status, fileName: url.lastPathComponent) }
                        }
                        photo = result.photo
                        cacheUse = result.cacheUse
                    }
                    if !settings.photos.contains(where: { $0.digest == photo.digest }) {
                        settings.photos.append(photo)
                        guard save() else {
                            settings.photos.removeAll { $0.uuid == photo.uuid }
                            throw VideoProcessorError.copyFailed
                        }
                    }
                    imported += 1
                } catch is CancellationError {
                    failures.append("\(url.lastPathComponent): import cancelled")
                } catch {
                    failures.append("\(url.lastPathComponent): \(message(for: error))")
                }
            }
            save()
            importMessage = imported == 0 ? "" : "Imported \(imported) \(imported == 1 ? "item" : "items")"
            if !failures.isEmpty {
                errorMessage = "Could not import:\n\(failures.map { "• \($0)" }.joined(separator: "\n"))"
                presentsErrorAlert = true
            }
            isWorking = false
        }
    }

    private static func importStatusText(_ status: VideoCompatibilityStatus, fileName: String) -> String {
        switch status {
        case .probing: return "Checking \(fileName)…"
        case .direct: return "Preparing \(fileName)…"
        case .converting(let fraction):
            guard let fraction else { return "Converting \(fileName)…" }
            return "Converting \(fileName)… \(Int((fraction * 100).rounded()))%"
        case .verifying: return "Verifying \(fileName)…"
        case .completed: return "Preparing \(fileName)…"
        }
    }

    func beginCertificateDiscovery() {
        guard !isWorking else { return }
        setLocallyDisconnected(false)
        errorMessage = nil; isWorking = true; connectionMessage = "Checking the TV certificate…"
        serverStatus = nil
        connectionActivity = .discovering
        AppDiagnostics.shared.record(.discovering)
        let address = host
        Task {
            defer { isWorking = false; connectionActivity = .idle }
            do {
                let pin = try await GlassTVClient(host: address, pin: nil, token: nil).discoverCertificate()
                guard address == host else { return }
                discoveredPin = pin; connectionMessage = "Compare this fingerprint with the TV."
            } catch {
                guard address == host else { return }
                recordConnectionFailure(error, operation: "certificate discovery")
                let failure = connectionFailureMessage(for: error)
                errorMessage = failure
                presentsErrorAlert = true
                connectionMessage = failure
            }
        }
    }

    func beginRePairing() {
        guard !isWorking else { return }
        pairingCode = ""
        discoveredPin = nil
        beginCertificateDiscovery()
    }

    func disconnect() {
        guard !isWorking else { return }
        AppDiagnostics.shared.record(.disconnected)
        appsSyncTask?.cancel()
        appsSyncTask = nil
        discoveredPin = nil
        pairingCode = ""
        serverStatus = nil
        lastStatusCheck = nil
        setLocallyDisconnected(true)
        appsChangesPending = settings.pendingAppsSync || settings.pendingInputSync
        if appsChangesPending {
            appsSyncMessage = "Saved locally. Shortcut changes will sync when the TV reconnects."
        }
        connectionMessage = isPaired ? "Disconnected locally. Connect when you are ready." : "Not paired"
    }

    func connect() {
        guard !isWorking else { return }
        AppDiagnostics.shared.record(.connecting)
        setLocallyDisconnected(false)
        if isPaired {
            refresh()
        } else {
            beginCertificateDiscovery()
        }
    }

    func cancelPairing() {
        guard !isWorking else { return }
        discoveredPin = nil
        pairingCode = ""
        connectionMessage = serverStatus != nil ? "Connected to TV" : (isPaired ? "Paired. Check the connection to continue." : "Not paired")
    }

    func confirmPairing() {
        guard !isWorking, let discoveredPin else { return }
        let code = pairingCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard code.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else {
            errorMessage = "Enter the six-digit code shown on the TV."
            presentsErrorAlert = true
            return
        }
        isWorking = true; errorMessage = nil; connectionMessage = "Pairing…"
        connectionActivity = .pairing
        let address = host
        Task {
            defer { isWorking = false; connectionActivity = .idle }
            do {
                let reply = try await GlassTVClient(host: address, pin: discoveredPin, token: nil).pair(code: code)
                guard address == host else { return }
                let saved = PairCredential(host: address, deviceId: reply.deviceId, token: reply.token, certificatePin: discoveredPin)
                try KeychainStore.save(saved)
                credential = saved
                promoteCurrentTV(deviceID: reply.deviceId)
                AppDiagnostics.shared.record(.paired)
                self.discoveredPin = nil; pairingCode = ""; setLocallyDisconnected(false); connectionMessage = "Paired with TV"
                connectionActivity = .checking
                await readStatus(using: saved, address: address)
            } catch {
                guard address == host else { return }
                recordConnectionFailure(error, operation: "pairing")
                let failure = connectionFailureMessage(for: error)
                errorMessage = failure
                presentsErrorAlert = true
                connectionMessage = "Pairing did not finish. \(failure)"
            }
        }
    }

    func refreshStatus() async {
        guard canAutoRefresh,
              TVAutomaticReconnectPolicy.shouldAttempt(now: Date(), nextAllowedAt: nextAutomaticStatusCheck) else { return }
        await refreshStatus(allowManualReconnect: false)
    }

    private func refreshStatus(allowManualReconnect: Bool) async {
        guard let credential, !isWorking, (allowManualReconnect || !isLocallyDisconnected) else { return }
        let automatic = !allowManualReconnect
        if automatic,
           !TVAutomaticReconnectPolicy.shouldAttempt(now: Date(), nextAllowedAt: nextAutomaticStatusCheck) { return }
        isWorking = true; errorMessage = nil; connectionMessage = "Checking the TV…"
        connectionActivity = .checking
        defer { isWorking = false; connectionActivity = .idle }
        await readStatus(
            using: credential,
            address: host,
            timeout: automatic ? TVAutomaticReconnectPolicy.statusRequestTimeout : nil,
            updatesAutomaticReconnectSchedule: true
        )
    }

    private func readStatus(
        using credential: PairCredential,
        address: String,
        timeout: TimeInterval? = nil,
        updatesAutomaticReconnectSchedule: Bool = false
    ) async {
        do {
            let response = try await GlassTVClient(host: address, pin: credential.certificatePin, token: credential.token).status(timeout: timeout)
            guard !Task.isCancelled, address == host, self.credential?.token == credential.token else { return }
            if updatesAutomaticReconnectSchedule { recordAutomaticStatusSuccess() }
            if serverStatus == nil { AppDiagnostics.shared.record(.connected) }
            serverStatus = response
            lastStatusCheck = Date()
            promoteCurrentTV(
                deviceID: response.deviceId,
                deviceName: response.deviceName,
                manufacturer: response.manufacturer,
                model: response.model
            )
            if let status = serverStatus {
                if !settings.pendingInputSync {
                    settings.selectedInputId = status.selectedInputId
                } else if status.selectedInputId == settings.selectedInputId {
                    settings.pendingInputSync = false
                }
                if let remoteWeather = status.playlist.weather {
                    switch SettingsSyncPolicy.action(pendingLocalEdit: settings.pendingWeatherSync, local: settings.weather, remote: remoteWeather) {
                    case .adoptRemote: settings.weather = remoteWeather
                    case .acknowledgeLocal: settings.pendingWeatherSync = false
                    case .deliverLocal: break
                    }
                }
                if let remoteApps = status.playlist.apps {
                    switch SettingsSyncPolicy.action(pendingLocalEdit: settings.pendingAppsSync, local: settings.apps, remote: remoteApps) {
                    case .adoptRemote: settings.apps = remoteApps
                    case .acknowledgeLocal: settings.pendingAppsSync = false
                    case .deliverLocal: break
                    }
                }
                if !settings.hasConfiguredShowTVInterface, let remoteShowTVInterface = status.playlist.showTVInterface {
                    settings.showTVInterface = remoteShowTVInterface
                    save()
                }
                var remoteLayout = status.playlist.layout ?? LayoutSettings()
                remoteLayout.weatherStyle = status.playlist.weather?.style ?? settings.weather.style
                remoteLayout.weatherStyleExplicitlySet = false
                settings.appliedLayout = remoteLayout
                if !settings.hasConfiguredLayout {
                    settings.layoutDraft = remoteLayout
                } else if !settings.layoutDraft.weatherStyleExplicitlySet {
                    settings.layoutDraft.weatherStyle = remoteLayout.weatherStyle
                }
                save()
                connectionMessage = "Connected to TV"
                reconcileApps(afterAuthenticatedStatus: status, address: address, credential: credential)
                reconcileWeather(afterAuthenticatedStatus: status, address: address, credential: credential)
            }
        } catch {
            guard !Task.isCancelled, address == host, self.credential?.token == credential.token else { return }
            serverStatus = nil
            if updatesAutomaticReconnectSchedule { recordAutomaticStatusFailure() }
            recordConnectionFailure(error, operation: "status refresh")
            let failure = connectionFailureMessage(for: error)
            connectionMessage = failure
        }
    }

    func refresh() {
        guard !isWorking else { return }
        setLocallyDisconnected(false)
        Task { await refreshStatus(allowManualReconnect: true) }
    }

    func sync() {
        guard canSync, let credential else {
            connectionMessage = isPaired ? "Connect to the TV before syncing." : "Pair with the TV before syncing."
            return
        }
        isWorking = true; errorMessage = nil; progress = 0; connectionMessage = "Preparing sync…"
        let client = GlassTVClient(host: host, pin: credential.certificatePin, token: credential.token)
        Task {
            do {
                var status = try await client.status()
                let active = activePhotos
                let stored = Set(status.storedImages.map(\.id))
                let toUpload = active.filter { !stored.contains($0.digest) }
                let bytesToUpload = try toUpload.reduce(0) { total, item in
                    let bytes: Int
                    if let knownBytes = item.byteCount { bytes = knownBytes }
                    else {
                        let attributes = try FileManager.default.attributesOfItem(atPath: ImageProcessor.url(for: item).path)
                        bytes = (attributes[.size] as? NSNumber)?.intValue ?? 0
                    }
                    return total + bytes
                }
                guard status.storage.usedBytes + bytesToUpload <= status.storage.maxBytes else { throw SyncError.insufficientTVStorage }
                for (index, photo) in toUpload.enumerated() {
                    connectionMessage = "Uploading \(photo.name)…"
                    if photo.mediaKind == .video { try await client.uploadVideo(file: ImageProcessor.url(for: photo), digest: photo.digest) }
                    else { try await client.upload(jpeg: Data(contentsOf: ImageProcessor.url(for: photo)), digest: photo.digest) }
                    progress = Double(index + 1) / Double(max(toUpload.count + 1, 1))
                }
                let playlist = reconciledPlaylist(from: status.playlist)
                let showTVInterface = settings.hasConfiguredShowTVInterface ? settings.showTVInterface : (status.playlist.showTVInterface ?? settings.showTVInterface)
                connectionMessage = "Updating rotation…"
                do {
                    _ = try await client.putPlaylist(baseRevision: status.revision, intervalSeconds: settings.intervalSeconds, shuffle: settings.shuffle, images: playlist, selectedInputId: settings.pendingInputSync ? settings.selectedInputId : status.selectedInputId, videoPlayback: settings.videoPlayback, loopVideoId: settings.loopVideoId, muteVideos: settings.muteVideos, slideshowEnabled: settings.slideshowEnabled, fitMode: settings.fitMode, showTVInterface: showTVInterface, weather: settings.pendingWeatherSync ? settings.weather : status.playlist.weather, apps: settings.pendingAppsSync ? settings.apps : status.playlist.apps, layout: status.playlist.layout)
                } catch let error as GlassTVClientError {
                    guard case .unexpectedResponse(409, _) = error else { throw error }
                    status = try await client.status()
                    let retriedShowTVInterface = settings.hasConfiguredShowTVInterface ? settings.showTVInterface : (status.playlist.showTVInterface ?? settings.showTVInterface)
                    _ = try await client.putPlaylist(baseRevision: status.revision, intervalSeconds: settings.intervalSeconds, shuffle: settings.shuffle, images: reconciledPlaylist(from: status.playlist), selectedInputId: settings.pendingInputSync ? settings.selectedInputId : status.selectedInputId, videoPlayback: settings.videoPlayback, loopVideoId: settings.loopVideoId, muteVideos: settings.muteVideos, slideshowEnabled: settings.slideshowEnabled, fitMode: settings.fitMode, showTVInterface: retriedShowTVInterface, weather: settings.pendingWeatherSync ? settings.weather : status.playlist.weather, apps: settings.pendingAppsSync ? settings.apps : status.playlist.apps, layout: status.playlist.layout)
                }
                progress = 1; serverStatus = try await client.status(); lastStatusCheck = Date(); connectionMessage = "Synced to TV"
            } catch {
                recordConnectionFailure(error, operation: "sync")
                let failure = connectionFailureMessage(for: error)
                errorMessage = failure
                presentsErrorAlert = true
                connectionMessage = "Sync stopped. \(failure)"
            }
            progress = nil; isWorking = false
        }
    }

    private func reconciledPlaylist(from remote: Playlist) -> [PlaylistImage] {
        let knownLocal = Set(settings.photos.map(\.digest))
        var images: [PlaylistImage] = []
        // Local ordering is authoritative for this Mac's active media. Preserve any remote media this library does not know about.
        for photo in activePhotos { images.append(PlaylistImage(id: photo.digest, name: String(photo.name.prefix(255)), kind: photo.mediaKind)) }
        for item in remote.images where !knownLocal.contains(item.id) { images.append(item) }
        return images
    }

    private func message(for error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }

    private func connectionFailureMessage(for error: Error) -> String {
        if error is GlassTVClientError { return message(for: error) }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .cannotConnectToHost, .cannotFindHost:
                return "Could not reach the TV. Check that it is powered on and on the same network. Open the Glass TV launcher if it is not already running."
            case .timedOut:
                return "The TV did not respond in time. Check that it is powered on and on the same network. Open the Glass TV launcher if it is not already running."
            case .notConnectedToInternet:
                return "Local network access is unavailable. Check Wi-Fi or Ethernet and allow Glass TV in System Settings > Privacy & Security > Local Network."
            case .networkConnectionLost:
                return "The connection to the TV was interrupted. Check both devices are on the same network, then try again."
            case .dataNotAllowed:
                return "Local network access is restricted. Enable Glass TV in System Settings > Privacy & Security > Local Network."
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot:
                return "A secure connection to the TV could not be established. Check the TV fingerprint and pair again."
            default:
                return "Connection failed (\(urlError.errorCode)): \(urlError.localizedDescription)"
            }
        }
        return message(for: error)
    }

    private func recordAutomaticStatusSuccess() {
        automaticStatusFailures = 0
        nextAutomaticStatusCheck = Date().addingTimeInterval(TVAutomaticReconnectPolicy.steadyStateInterval)
    }

    private func recordAutomaticStatusFailure() {
        automaticStatusFailures += 1
        nextAutomaticStatusCheck = Date().addingTimeInterval(
            TVAutomaticReconnectPolicy.refreshDelay(afterFailures: automaticStatusFailures)
        )
    }

    private func recordConnectionFailure(_ error: Error, operation: String) {
        serverStatus = nil
        lastStatusCheck = Date()
        connectionMessage = connectionFailureMessage(for: error)
        let nsError = error as NSError
        let event: AppDiagnostics.Event
        switch operation {
        case "certificate discovery": event = .discoveryFailed
        case "pairing": event = .pairingFailed
        case "status refresh": event = .statusFailed
        default: event = .syncFailed
        }
        AppDiagnostics.shared.record(event, errorCode: nsError.code)
        connectionLog.error("\(operation, privacy: .public) failed: domain=\(nsError.domain, privacy: .public), code=\(nsError.code, privacy: .public), message=\(nsError.localizedDescription, privacy: .private(mask: .hash))")
    }
}

private enum SyncError: LocalizedError {
    case insufficientTVStorage
    var errorDescription: String? { "The selected media exceeds available TV storage. Photos and videos removed from rotation stay stored on TV." }
}

enum AppsSyncScheduling {
    static func shouldScheduleFollowUp(acceptedApps: Bool, changedDuringApply: Bool) -> Bool {
        acceptedApps && changedDuringApply
    }

    static func shouldWaitForOtherWork(isWorking: Bool, isApplyingApps: Bool) -> Bool {
        isWorking && !isApplyingApps
    }

    static func selfTestsPass() -> Bool {
        shouldScheduleFollowUp(acceptedApps: true, changedDuringApply: true)
            && !shouldScheduleFollowUp(acceptedApps: false, changedDuringApply: true)
            && !shouldScheduleFollowUp(acceptedApps: true, changedDuringApply: false)
            && shouldWaitForOtherWork(isWorking: true, isApplyingApps: false)
            && !shouldWaitForOtherWork(isWorking: true, isApplyingApps: true)
            && !shouldWaitForOtherWork(isWorking: false, isApplyingApps: false)
    }
}
