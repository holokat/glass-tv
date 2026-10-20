import SwiftUI

/// The media page's connection preview and playback controls share one native glass surface.
struct MediaInspector<ConnectionControls: View>: View {
    @ObservedObject var model: CompanionModel
    let previewItem: LocalPhoto?
    let connectionControls: ConnectionControls

    init(model: CompanionModel, previewItem: LocalPhoto?, @ViewBuilder connectionControls: () -> ConnectionControls) {
        self.model = model
        self.previewItem = previewItem
        self.connectionControls = connectionControls()
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 8) {
                        Circle().fill(model.tvConnectionState == .connected ? Color.green : Color.secondary)
                            .frame(width: 9, height: 9)
                        Text(model.tvConnectionState == .checking ? "Checking TV…" : (model.tvConnectionState == .connected ? "TV connected" : "TV not connected"))
                            .font(.system(size: 14, weight: .medium))
                    }
                    TVPreviewFrame(item: previewItem)
                    HStack(alignment: .center, spacing: 12) {
                        Text(model.isConnected ? "Paired TV" : "TV preview")
                            .font(.system(size: 17, weight: .medium))
                        Spacer(minLength: 0)
                        if model.isConnected {
                            Button("Disconnect") { model.disconnect() }
                                .nativeGlassButton().disabled(model.isWorking)
                        } else if model.isPaired {
                            Button(model.connectionActivity == .idle ? "Connect" : "Checking TV…") { model.connect() }
                                .nativeGlassButton().disabled(model.isWorking)
                        } else {
                            Button("Connect") { model.connect() }
                                .nativeGlassButton().disabled(model.isWorking)
                        }
                    }
                    Divider()
                    if model.isPaired, model.discoveredPin == nil {
                        DisclosureGroup("Connection") { connectionControls.padding(.top, 10) }
                            .font(.system(size: 13))
                    } else { connectionControls }
                    playbackSettings
                    if let progress = model.progress {
                        VStack(alignment: .leading, spacing: 8) {
                            ProgressView(value: progress)
                            Text(model.connectionMessage).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(22)
            }
            .scrollIndicators(.hidden)
            Button {
                guard model.canSync else { return }
                model.setSlideshowEnabled(true)
                model.sync()
            } label: {
                HStack(spacing: 9) {
                    Icon("play", fallback: "Play").frame(width: 18, height: 18)
                    Text("Start slideshow").fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity, minHeight: 30)
            }
            .nativeGlassButton()
            .disabled(!model.canSync)
            .padding(.horizontal, 22)
            .padding(.bottom, 22)
        }
        .frame(width: 334)
        .frame(maxHeight: .infinity, alignment: .top)
        .nativeGlass(corner: 26)
    }

    private var playbackSettings: some View {
        VStack(spacing: 2) {
            settingRow("Slideshow") {
                Toggle("Slideshow", isOn: Binding(get: { model.slideshowEnabled }, set: model.setSlideshowEnabled))
                    .labelsHidden().toggleStyle(.switch)
            }
            settingRow("Rotate every") {
                Picker("Rotate every", selection: Binding(get: { model.interval }, set: model.setInterval)) {
                    ForEach(RotationInterval.allCases) { Text($0.label).tag($0.rawValue) }
                }.labelsHidden().fixedSize()
            }
            settingRow("Fit to screen") {
                Picker("Fit to screen", selection: Binding(get: { model.fitMode }, set: model.setFitMode)) {
                    ForEach(FitMode.allCases) { Text($0.label).tag($0) }
                }.labelsHidden().fixedSize()
            }
            VStack(alignment: .leading, spacing: 2) {
                settingRow("Show TV interface") {
                    Toggle("Show TV interface", isOn: Binding(get: { model.showTVInterface }, set: model.setShowTVInterface))
                        .labelsHidden().toggleStyle(.switch)
                        .help("When hidden, press a navigation button on the TV remote to reveal the interface temporarily. Choose Start slideshow to apply.")
                }
                Text("Show the clock, shortcuts, and weather during media playback.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if !model.videos.isEmpty {
                settingRow("Video playback") {
                    Picker("Video playback", selection: Binding(get: { model.videoPlayback }, set: model.setVideoPlayback)) {
                        ForEach(VideoPlayback.allCases) { Text($0.label).tag($0) }
                    }.labelsHidden().fixedSize()
                }
                settingRow("Mute video audio") {
                    Toggle("Mute video audio", isOn: Binding(get: { model.muteVideos }, set: model.setMuteVideos))
                        .labelsHidden().toggleStyle(.switch)
                }
            }
        }
    }

    private func settingRow<Control: View>(_ title: String, @ViewBuilder control: () -> Control) -> some View {
        HStack(spacing: 12) {
            Text(title).font(.system(size: 14)).lineLimit(1)
            Spacer(minLength: 6)
            control().controlSize(.regular)
        }
        .frame(minHeight: 42)
    }
}
