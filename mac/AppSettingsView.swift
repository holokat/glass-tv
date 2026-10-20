import Foundation
import AppKit
import ServiceManagement
import SwiftUI

struct AppSettingsView: View {
    @ObservedObject private var diagnostics = AppDiagnostics.shared

    var body: some View {
        TabView {
            GeneralSettingsTab()
                .tabItem { settingsTabLabel(icon: "settings", title: "General") }

            StorageSettingsTab()
                .tabItem { settingsTabLabel(icon: "folder", title: "Storage") }

            DiagnosticsSettingsTab(diagnostics: diagnostics)
                .tabItem { settingsTabLabel(icon: "info", title: "Diagnostics") }

            AboutSettingsTab()
                .tabItem { settingsTabLabel(icon: "tv", title: "About") }
        }
        .frame(width: 540, height: 380)
    }

    @ViewBuilder
    private func settingsTabLabel(icon: String, title: String) -> some View {
        HStack(spacing: 6) {
            Icon(icon, fallback: title)
                .frame(width: 15, height: 15)
            Text(title)
        }
    }
}

private struct GeneralSettingsTab: View {
    @AppStorage(AppPreferences.keepRunningWhenWindowClosesKey) private var keepRunningWhenWindowCloses = true
    @State private var launchAtLogin = false
    @State private var launchAtLoginMessage: String?
    @State private var launchAtLoginError: String?

    var body: some View {
        Form {
            Section("Startup") {
                Toggle("Launch at login", isOn: Binding(
                    get: { launchAtLogin },
                    set: { updateLaunchAtLogin($0) }
                ))

                if let launchAtLoginMessage {
                    Text(launchAtLoginMessage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let launchAtLoginError {
                    Text(launchAtLoginError)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            Section("Background") {
                Toggle("Keep running when window closes", isOn: $keepRunningWhenWindowCloses)
                Text("Keep movie streaming available.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding(20)
        .task { refreshLaunchAtLoginStatus() }
        .onAppear { refreshLaunchAtLoginStatus() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            refreshLaunchAtLoginStatus()
        }
    }

    private func refreshLaunchAtLoginStatus() {
        switch SMAppService.mainApp.status {
        case .enabled:
            launchAtLogin = true
            launchAtLoginMessage = nil
        case .requiresApproval:
            launchAtLogin = true
            launchAtLoginMessage = "Approval required in System Settings > General > Login Items."
        case .notRegistered:
            launchAtLogin = false
            launchAtLoginMessage = nil
        case .notFound:
            launchAtLogin = false
            launchAtLoginMessage = "Launch at login is unavailable."
        @unknown default:
            launchAtLogin = false
            launchAtLoginMessage = "Launch at login is unavailable."
        }
    }

    private func updateLaunchAtLogin(_ enabled: Bool) {
        launchAtLoginError = nil
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            launchAtLoginError = "Could not update launch at login: \(error.localizedDescription)"
        }
        refreshLaunchAtLoginStatus()
    }
}

private struct StorageSettingsTab: View {
    @State private var cacheSnapshot: ConversionCacheSnapshot?
    @State private var cacheLimitBytes: Int64 = 0
    @State private var didLoadCache = false
    @State private var clearConfirmationPresented = false
    @State private var statusMessage = ""

    var body: some View {
        Form {
            Section("Conversion cache") {
                LabeledContent("Used") {
                    Text(cacheSnapshot.map { byteCount($0.sizeBytes) } ?? "Loading…")
                        .monospacedDigit()
                }

                Picker("Limit", selection: $cacheLimitBytes) {
                    Text("Unlimited").tag(Int64(0))
                    Text("1 GB").tag(Int64(1_000_000_000))
                    Text("5 GB").tag(Int64(5_000_000_000))
                    Text("10 GB").tag(Int64(10_000_000_000))
                    Text("25 GB").tag(Int64(25_000_000_000))
                }
                .onChange(of: cacheLimitBytes) { _ in
                    guard didLoadCache else { return }
                    Task { await updateCacheLimit() }
                }

                Text("New conversions stop when this limit is reached.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button("Clear cache") {
                    clearConfirmationPresented = true
                }
                .nativeGlassButton()
                .disabled(cacheSnapshot == nil || cacheSnapshot?.fileCount == 0)

                if !statusMessage.isEmpty {
                    Text(statusMessage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
        .padding(20)
        .task { await refreshCache() }
        .confirmationDialog(
            "Clear video compatibility cache?",
            isPresented: $clearConfirmationPresented,
            titleVisibility: .visible
        ) {
            Button("Clear cache", role: .destructive) {
                Task { await clearCache() }
            }
        } message: {
            Text("Remove unused converted videos? Originals and library media are kept.")
        }
    }

    private func refreshCache() async {
        let snapshot = await ConversionCache.shared.snapshot()
        cacheSnapshot = snapshot
        cacheLimitBytes = snapshot.limitBytes ?? 0
        didLoadCache = true
    }

    private func updateCacheLimit() async {
        await ConversionCache.shared.setLimitBytes(cacheLimitBytes == 0 ? nil : cacheLimitBytes)
        await refreshCache()
    }

    private func clearCache() async {
        let result = await ConversionCache.shared.clearUserTriggered()
        statusMessage = result.removedCount == 0
            ? "No unused converted videos were removed."
            : "Cleared \(byteCount(result.removedBytes))."
        if result.skippedCount > 0 {
            statusMessage += " \(result.skippedCount) \(result.skippedCount == 1 ? "file" : "files") kept."
        }
        await refreshCache()
    }

    private func byteCount(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct DiagnosticsSettingsTab: View {
    @ObservedObject var diagnostics: AppDiagnostics
    @State private var exportError: String?

    var body: some View {
        Form {
            Section("Connection log") {
                ScrollView(.vertical) {
                    Text(diagnostics.logText.isEmpty ? "No connection events yet." : diagnostics.logText)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(diagnostics.logText.isEmpty ? .secondary : .primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(height: 108)

                Button("Export…", action: exportLog)
                    .nativeGlassButton()

                if let exportError {
                    Text(exportError)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        }
        .formStyle(.grouped)
        .padding(20)
    }

    private func exportLog() {
        do {
            try diagnostics.export()
            exportError = nil
        } catch {
            exportError = "Could not export the connection log: \(error.localizedDescription)"
        }
    }
}

private struct AboutSettingsTab: View {
    private var version: String {
        let release = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        switch (release, build) {
        case let (release?, build?): return "\(release) (\(build))"
        case let (release?, nil): return release
        default: return "Unavailable"
        }
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Version") {
                    Text(version).monospacedDigit()
                }
            }

            Section("Credits") {
                LabeledContent("Forecast data") {
                    Link("Open-Meteo", destination: URL(string: "https://open-meteo.com/")!)
                }
                LabeledContent("Icons") {
                    Text("Central Icons")
                }
                LabeledContent("Artwork") {
                    Text("AI-generated")
                }
            }
        }
        .formStyle(.grouped)
        .padding(20)
    }
}
