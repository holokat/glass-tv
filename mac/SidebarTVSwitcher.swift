import SwiftUI

/// The sidebar owns TV selection; pairing continues in the existing TV page.
struct SidebarTVSwitcher: View {
    @ObservedObject var model: CompanionModel
    let onManage: () -> Void
    @State private var showsTVs = false
    @State private var showsAddTV = false
    @State private var showsRename = false
    @State private var address = ""
    @State private var name = ""

    var body: some View {
        VStack(spacing: 0) {
            Divider().padding(.horizontal, 16)
            Button { showsTVs.toggle() } label: {
                HStack(spacing: 10) {
                    Icon("tv", fallback: "TV")
                        .frame(width: 27, height: 27)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Circle().fill(model.isConnected ? Color.green : Color.secondary)
                                .frame(width: 7, height: 7)
                                .accessibilityHidden(true)
                            Text(model.activeTVName)
                                .font(.system(size: 13, weight: .medium))
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .help(model.activeTVName)
                        }
                        Text(statusText)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Icon("more_horizontal", fallback: "Manage TVs")
                        .frame(width: 16, height: 16)
                        .foregroundStyle(.secondary)
                        .frame(width: 34, height: 34)
                        .background(.ultraThinMaterial, in: Circle())
                        .overlay(Circle().fill(Color.black.opacity(0.16)))
                        .overlay(Circle().strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5))
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 15)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Manage TVs")
            .accessibilityLabel("\(model.activeTVName), \(statusText). Manage TVs")
            .popover(isPresented: $showsTVs, arrowEdge: .bottom) {
                tvPicker
            }
            .padding(.horizontal, 6)
            .padding(.bottom, 4)
        }
        .sheet(isPresented: $showsAddTV) { addTVSheet }
        .sheet(isPresented: $showsRename) { renameSheet }
    }

    private var statusText: String {
        if model.isConnected { return "TV connected" }
        switch model.tvConnectionState {
        case .checking: return "Checking connection…"
        case .discovering: return "Finding TV…"
        case .awaitingCode: return "Confirm pairing"
        case .pairing: return "Pairing TV…"
        case .unpaired: return "Not paired"
        case .disconnected, .connected: return "Not connected"
        }
    }

    private var tvPicker: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Your TVs").font(.headline)
            if model.savedTVs.isEmpty {
                Text("Connect a TV to save it here.")
                    .font(.subheadline).foregroundStyle(.secondary)
            } else {
                ScrollView {
                    VStack(spacing: 4) {
                        ForEach(model.savedTVs) { tv in
                            Button {
                                if tv.host != model.host {
                                    guard model.selectTV(host: tv.host) else { return }
                                    model.connect()
                                } else if !model.isConnected {
                                    if model.tvConnectionState == .awaitingCode {
                                        onManage()
                                    } else {
                                        model.connect()
                                    }
                                }
                                showsTVs = false
                            } label: {
                                HStack(spacing: 10) {
                                    Icon("tv", fallback: "TV").frame(width: 24, height: 24)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(tv.displayName).font(.system(size: 13, weight: .medium))
                                            .lineLimit(1)
                                            .truncationMode(.tail)
                                            .help(tv.displayName)
                                        Text(tv.host == model.host ? statusText : "Saved TV · \(tv.host)")
                                            .font(.caption).foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 8)
                                    if tv.host == model.host {
                                        Icon("check", fallback: "Selected").frame(width: 15, height: 15)
                                    }
                                }
                                .padding(10)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(tv.host == model.host ? Color.primary.opacity(0.07) : .clear,
                                            in: RoundedRectangle(cornerRadius: 8))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(model.isWorking)
                            .accessibilityLabel("\(tv.displayName), \(tv.host == model.host ? statusText : "saved TV")")
                        }
                    }
                }
                .frame(height: min(252, CGFloat(model.savedTVs.count) * 65))
            }
            Divider()
            Button {
                showsTVs = false
                address = ""
                name = ""
                showsAddTV = true
            } label: {
                HStack(spacing: 8) {
                    Icon("add", fallback: "Add").frame(width: 16, height: 16)
                    Text("Connect another TV").lineLimit(1)
                    Spacer()
                }
            }
            .nativeGlassButton()
            .disabled(model.isWorking)
            HStack {
                Button("TV settings") {
                    showsTVs = false
                    onManage()
                }.nativeGlassButton()
                Spacer()
                Button("Rename") {
                    name = model.activeTVName
                    showsTVs = false
                    showsRename = true
                }
                .nativeGlassButton()
                .disabled(model.isWorking || !model.savedTVs.contains(where: { $0.host == model.host }))
            }
            if model.isConnected {
                Button("Disconnect") {
                    model.disconnect()
                    showsTVs = false
                }
                .nativeGlassButton()
                .disabled(model.isWorking)
            }
        }
        .padding(16)
        .frame(width: 220)
    }

    private var addTVSheet: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Connect another TV").font(.title2.weight(.semibold))
            Text("On the other TV, open Glass TV Settings and choose Pair Mac. Enter the address shown there.")
                .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 12) {
                TextField("TV address", text: $address)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel("TV address")
                TextField("Name (optional)", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel("TV name, optional")
            }
            HStack {
                Button("Cancel") { showsAddTV = false }.nativeGlassButton()
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button("Continue") {
                    guard model.addTV(host: address.trimmingCharacters(in: .whitespacesAndNewlines),
                                      name: name.trimmingCharacters(in: .whitespacesAndNewlines)) else { return }
                    showsAddTV = false
                    onManage()
                }
                .nativeGlassButton()
                .keyboardShortcut(.defaultAction)
                .disabled(!validAddress || model.isWorking)
            }
        }
        .padding(24)
        .frame(width: 400)
    }

    private var renameSheet: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Rename TV").font(.title2.weight(.semibold))
            TextField("TV name", text: $name).textFieldStyle(.roundedBorder)
                .accessibilityLabel("TV name")
            HStack {
                Button("Cancel") { showsRename = false }.nativeGlassButton()
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button("Save") {
                    model.renameActiveTV(name.trimmingCharacters(in: .whitespacesAndNewlines))
                    showsRename = false
                }
                .nativeGlassButton()
                .keyboardShortcut(.defaultAction)
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isWorking)
            }
        }
        .padding(24)
        .frame(width: 320)
    }

    private var validAddress: Bool {
        let parts = address.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy {
            !$0.isEmpty && $0.allSatisfy(\.isNumber) && Int($0).map { (0...255).contains($0) } == true
        }
    }
}
