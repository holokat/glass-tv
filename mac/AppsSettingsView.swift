import AVFoundation
import SwiftUI

private struct ShortcutRowFramePreference: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

struct AppsSettingsView: View {
    @ObservedObject var model: CompanionModel
    @ObservedObject var movieBridge: MovieLibraryBridge

    @State private var settings = AppsSettings()
    @State private var didLoadSettings = false
    @State private var draggedShortcutID: String?
    @State private var dropTarget: ShortcutDropTarget?
    @State private var shortcutRowFrames: [String: CGRect] = [:]
    @State private var navigationSoundPlayer: AVAudioPlayer?

    private struct ShortcutDropTarget: Equatable {
        let id: String
        let insertsAfter: Bool
    }

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 18) {
                PageHeading("Apps", subtitle: "Choose which TV launcher shortcuts appear on the Glass TV home screen. Changes sync automatically when the TV is connected.")
                shortcutsCard
            }
            .frame(minWidth: 360, maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            VStack(alignment: .leading, spacing: 18) {
                shortcutPreviewCard
                deliveryCard
                inputMappingCard
            }
            .frame(minWidth: 360, maxWidth: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, AppDesign.pageInset)
        .padding(.top, AppDesign.pageTopInset)
        .padding(.bottom, 28)
        .foregroundStyle(.white)
        .onAppear { hydrate(from: model.apps) }
        .onChange(of: model.apps) { remote in
            guard !model.hasPendingAppsSync, remote != settings else { return }
            hydrate(from: remote)
        }
        .onChange(of: settings) { value in
            guard didLoadSettings else { return }
            model.updateApps(value)
        }
        .onDisappear { stopNavigationSoundPreview() }
    }

    private var shortcutPreviewCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Preview")
                .font(.headline)
            let apps = AppShortcutCatalog.definitions(for: settings)
            if apps.isEmpty {
                Text("Enable an app to preview your shortcuts.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 100)
            } else {
                GeometryReader { proxy in
                    let screen = CGSize(width: proxy.size.width, height: proxy.size.width * 9 / 16)
                    let metrics = LayoutGeometry.appCarouselMetrics(
                        appCount: apps.count,
                        appIconScale: 1.5,
                        appSpacingScale: model.layoutDraft.effectiveAppSpacingScale,
                        safeAreaPadding: 0,
                        appsShowLabels: true,
                        shortcutStyle: settings.shortcutStyle
                    )
                    LayoutAppRow(
                        apps: apps,
                        screenSize: screen,
                        showLabels: true,
                        shortcutStyle: settings.shortcutStyle,
                        metrics: metrics
                    )
                    .id(settings.shortcutStyle)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .aspectRatio(4, contentMode: .fit)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    private var shortcutsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            shortcutStylePicker
            ScrollView(.vertical) {
                LazyVStack(spacing: 0) {
                    ForEach(shortcutRows) { row in
                        shortcutRow(row)
                        if row.id != shortcutRows.last?.id {
                            Divider().opacity(0.45)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .coordinateSpace(name: "shortcutOrderList")
            .onPreferenceChange(ShortcutRowFramePreference.self) { shortcutRowFrames = $0 }
            .frame(minHeight: 180, maxHeight: .infinity)
            HStack(alignment: .top, spacing: 10) {
                Icon("info", fallback: "Info")
                    .frame(width: 17, height: 17)
                    .foregroundStyle(.secondary)
                Text("Select a missing app on TV to open its Google Play install page.")
                    .fixedSize(horizontal: false, vertical: true)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 14)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .nativeGlass(corner: 18)
    }

    private struct ShortcutRow: Identifiable {
        let definition: AppShortcutDefinition
        let subtitle: String
        var id: String { definition.id }
    }

    private var shortcutRows: [ShortcutRow] {
        return AppShortcutCatalog.definitions(for: settings, includeDisabled: true).map { definition in
            let id = definition.id
            let subtitle: String
            if id == "movies" { subtitle = "Share this Mac’s movie library with the TV." }
            else if id == "hdmi" { subtitle = "Show a \(settings.hdmiStyle.label) shortcut on TV." }
            else { subtitle = "Show the \(definition.title) shortcut on TV." }
            return ShortcutRow(definition: definition, subtitle: subtitle)
        }
    }

    private var shortcutStylePicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Shortcut style")
                .font(.headline)
            Picker("Shortcut style", selection: $settings.shortcutStyle) {
                ForEach(ShortcutStyle.allCases) { style in
                    Text(style.title).tag(style)
                }
            }
            .pickerStyle(.radioGroup)
            .horizontalRadioGroupLayout()
            .labelsHidden()
            .accessibilityLabel("Shortcut style")
            Divider().opacity(0.45)
            navigationSoundControls
        }
        .padding(.vertical, 14)
    }

    private var navigationSoundControls: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                Text("Navigation sound")
                    .font(.subheadline)
                navigationSoundMenu
                previewNavigationSoundButton
                Spacer(minLength: 4)
                navigationSoundVolumeSlider
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Text("Navigation sound")
                        .font(.subheadline)
                    navigationSoundMenu
                    previewNavigationSoundButton
                }
                HStack(spacing: 10) {
                    Text("Volume")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    navigationSoundVolumeSlider
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var navigationSoundMenu: some View {
        Picker("Navigation sound", selection: navigationSoundBinding) {
            ForEach(AppsSettings.NavigationSound.allCases) { sound in
                Text(sound.title).tag(sound)
            }
        }
        .pickerStyle(.menu)
        .labelsHidden()
        .accessibilityLabel("Navigation sound")
    }

    private var previewNavigationSoundButton: some View {
        Button(action: previewNavigationSound) {
            HStack(spacing: 6) {
                Icon("play", fallback: "Preview")
                    .frame(width: 14, height: 14)
                Text("Preview")
            }
        }
        .nativeGlassButton(controlSize: .small)
        .disabled(settings.effectiveNavigationSound == .off || navigationSoundURL == nil)
    }

    private var navigationSoundVolumeSlider: some View {
        Slider(value: navigationSoundVolumeBinding, in: 0...1)
            .frame(width: 88)
            .accessibilityLabel("Navigation sound volume")
            .accessibilityValue(settings.effectiveNavigationSoundVolume.formatted(.percent.precision(.fractionLength(0))))
    }

    private func shortcutRow(id: String, icon: String, fallback: String, title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            HStack(spacing: 12) {
                Icon("drag_grip", fallback: "")
                    .frame(width: 16, height: 16)
                    .opacity(0.55)
                    .accessibilityLabel("Reorder \(title)")
                    .help("Drag to change TV order")
                Icon(icon, fallback: fallback)
                    .frame(width: icon == "hulu_logo" || icon == "prime_video_logo" ? 32 : 28, height: 28)
                    .frame(width: 40, height: 40)
                    .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.headline)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 12)
            }
            .contentShape(Rectangle())
            .gesture(shortcutReorderGesture(for: id))
            Toggle("Show \(title) shortcut", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .accessibilityLabel("Show \(title) shortcut")
        }
        .padding(.vertical, 14)
    }

    @ViewBuilder
    private func shortcutRow(_ row: ShortcutRow) -> some View {
        Group {
            if row.id == "hdmi" {
                VStack(alignment: .leading, spacing: 12) {
                    shortcutRow(id: row.id, icon: row.definition.minimalIcon, fallback: "HDMI", title: "HDMI", subtitle: row.subtitle, isOn: $settings.hdmiEnabled)
                    Picker("Shortcut appearance", selection: $settings.hdmiStyle) {
                        ForEach(AppsSettings.HDMIStyle.allCases) { style in
                            Text(style.label).tag(style)
                        }
                    }
                    .pickerStyle(.menu)
                    .disabled(!settings.hdmiEnabled)
                    .padding(.leading, 80)
                }
            } else {
                shortcutRow(id: row.id, icon: row.definition.minimalIcon, fallback: row.definition.title, title: row.definition.title, subtitle: row.subtitle, isOn: enabledBinding(for: row.id))
            }
        }
        .contentShape(Rectangle())
        .contextMenu {
            Button("Move up") { settings.moveShortcut(id: row.id, by: -1) }
                .disabled(shortcutRows.first?.id == row.id)
            Button("Move down") { settings.moveShortcut(id: row.id, by: 1) }
                .disabled(shortcutRows.last?.id == row.id)
        }
        .background {
            GeometryReader { proxy in
                Color.clear.preference(
                    key: ShortcutRowFramePreference.self,
                    value: [row.id: proxy.frame(in: .named("shortcutOrderList"))]
                )
            }
        }
        .overlay(alignment: .top) {
            if dropTarget?.id == row.id, dropTarget?.insertsAfter == false {
                Rectangle().fill(.tint).frame(height: 2).allowsHitTesting(false)
            }
        }
        .overlay(alignment: .bottom) {
            if dropTarget?.id == row.id, dropTarget?.insertsAfter == true {
                Rectangle().fill(.tint).frame(height: 2).allowsHitTesting(false)
            }
        }
    }

    private func shortcutReorderGesture(for id: String) -> some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .named("shortcutOrderList"))
            .onChanged { value in
                if draggedShortcutID == nil { draggedShortcutID = id }
                guard draggedShortcutID == id else { return }
                updateDropTarget(for: value.location, draggedID: id)
            }
            .onEnded { value in
                guard draggedShortcutID == id else { return }
                updateDropTarget(for: value.location, draggedID: id)
                if let destinationID = dropTarget?.id, destinationID != id {
                    settings.moveShortcut(id, onto: destinationID)
                }
                draggedShortcutID = nil
                dropTarget = nil
            }
    }

    private func updateDropTarget(for location: CGPoint, draggedID: String) {
        let candidates = shortcutRows.compactMap { row -> (id: String, frame: CGRect)? in
            guard let frame = shortcutRowFrames[row.id] else { return nil }
            return (row.id, frame)
        }
        guard let contentBounds = candidates.map(\.frame).reduce(nil, { partial, frame in
            partial.map { $0.union(frame) } ?? frame
        }), contentBounds.contains(location),
        let candidate = candidates.min(by: {
            abs($0.frame.midY - location.y) < abs($1.frame.midY - location.y)
        }), candidate.frame.contains(location), candidate.id != draggedID,
        let source = shortcutRows.firstIndex(where: { $0.id == draggedID }),
        let destination = shortcutRows.firstIndex(where: { $0.id == candidate.id }) else {
            dropTarget = nil
            return
        }
        dropTarget = ShortcutDropTarget(id: candidate.id, insertsAfter: source < destination)
    }

    private var navigationSoundBinding: Binding<AppsSettings.NavigationSound> {
        Binding(
            get: { settings.effectiveNavigationSound },
            set: { sound in
                let changed = settings.effectiveNavigationSound != sound
                settings.navigationSound = sound
                if sound == .off { stopNavigationSoundPreview() }
                else if changed { previewNavigationSound() }
            }
        )
    }

    private var navigationSoundVolumeBinding: Binding<Double> {
        Binding(
            get: { settings.effectiveNavigationSoundVolume },
            set: { settings.navigationSoundVolume = min(1, max(0, $0)) }
        )
    }

    private var navigationSoundURL: URL? {
        let sound = settings.effectiveNavigationSound
        guard sound != .off else { return nil }
        return Bundle.main.url(forResource: sound.rawValue, withExtension: "mp3", subdirectory: "navigation-sounds")
    }

    private func previewNavigationSound() {
        guard let url = navigationSoundURL else { return }
        stopNavigationSoundPreview()
        guard let player = try? AVAudioPlayer(contentsOf: url) else { return }
        player.volume = Float(settings.effectiveNavigationSoundVolume)
        player.prepareToPlay()
        player.play()
        navigationSoundPlayer = player
    }

    private func stopNavigationSoundPreview() {
        navigationSoundPlayer?.stop()
        navigationSoundPlayer = nil
    }

    private func serviceBinding(_ id: String) -> Binding<Bool> {
        Binding(
            get: { settings.isServiceEnabled(id) },
            set: { settings.setService(id, enabled: $0) }
        )
    }

    private func enabledBinding(for id: String) -> Binding<Bool> {
        switch id {
        case "netflix": return $settings.netflixEnabled
        case "radio": return $settings.radioEnabled
        case "prime_video": return $settings.primeVideoEnabled
        case "spotify": return $settings.spotifyEnabled
        case "twitch": return $settings.twitchEnabled
        case "hulu": return $settings.huluEnabled
        case "movies": return $settings.moviesEnabled
        default: return serviceBinding(id)
        }
    }

    private var deliveryCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Icon("apps", fallback: "Apps")
                    .frame(width: 18, height: 18)
                Text("TV shortcuts")
                    .font(.headline)
                Spacer()
                Button {
                    model.applyApps()
                } label: {
                    HStack(spacing: 8) {
                        Icon("upload", fallback: "Apply")
                            .frame(width: 17, height: 17)
                        Text("Sync now")
                    }
                }
                .nativeGlassButton()
                .disabled(!model.isConnected || model.isWorking)
            }

            Text(deliveryMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            HStack(alignment: .top, spacing: 10) {
                Icon("movies", fallback: "Movies")
                    .frame(width: 17, height: 17)
                    .foregroundStyle(.secondary)
                Text(movieBridge.statusMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Divider().opacity(0.45)

            HStack(alignment: .top, spacing: 10) {
                Icon("radio", fallback: "Radio")
                    .frame(width: 17, height: 17)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Radio Browser")
                        .font(.subheadline.weight(.medium))
                    Text("Only stream-checked HTTPS stations, 128 kbps or higher. 320 kbps first.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    @ViewBuilder
    private var inputMappingCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Icon("controller", fallback: "Input")
                    .frame(width: 18, height: 18)
                Text("HDMI input mapping")
                    .font(.headline)
            }

            if let status = model.serverStatus, !status.inputs.isEmpty {
                Picker("TV input", selection: selectedInputBinding) {
                    Text("Use TV default").tag(Optional<String>.none)
                    ForEach(status.inputs) { input in
                        Text(inputLabel(input)).tag(Optional(input.id))
                    }
                }
                .pickerStyle(.menu)
                Text("Changes to this input sync automatically when connected.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text("Connect to the TV to read available HDMI inputs. Shortcut choices remain editable offline.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    private var selectedInputBinding: Binding<String?> {
        Binding(
            get: { model.selectedInputId },
            set: { model.setInput($0) }
        )
    }

    private var hdmiIcon: String {
        switch settings.hdmiStyle {
        case .switch: return "switch_logo"
        case .ps5: return "controller"
        case .hdmi: return "hdmi"
        }
    }

    private var deliveryMessage: String {
        if !model.appsSyncMessage.isEmpty { return model.appsSyncMessage }
        if model.isConnected { return "Shortcuts sync automatically while the TV is connected." }
        return model.isPaired ? "Saved locally. Shortcuts will sync when the TV reconnects." : "Saved locally. Pair with the TV to sync shortcuts."
    }

    private func inputLabel(_ input: TVInput) -> String {
        let label = input.customLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        return label?.isEmpty == false ? label! : input.label
    }

    private func hydrate(from source: AppsSettings) {
        didLoadSettings = false
        settings = source
        DispatchQueue.main.async { didLoadSettings = true }
    }
}
