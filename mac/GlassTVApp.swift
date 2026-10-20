import SwiftUI
import AppKit

@main
struct GlassTVApp: App {
    @StateObject private var model = CompanionModel()
    @StateObject private var movies = MoviesModel()
    @StateObject private var movieBridge = MovieLibraryBridge()
    @State private var movieServer = MovieHTTPServer()
    @NSApplicationDelegateAdaptor(GlassTVApplicationDelegate.self) private var applicationDelegate

    init() {
        AppPreferences.registerDefaults()
        _ = AppDiagnostics.shared
        CaptureRunner.configure(arguments: CommandLine.arguments)
    }

    var body: some Scene {
        WindowGroup("Glass TV") {
            ContentView(movies: movies, movieBridge: movieBridge, movieServer: movieServer)
                .environmentObject(model)
                .frame(minWidth: 1100, minHeight: 680)
                .preferredColorScheme(.dark)
                .onAppear { CaptureRunner.scheduleIfNeeded() }
        }
        .defaultSize(width: 1536, height: 1024)
        .commands {
            CommandGroup(replacing: .newItem) { }
        }

        Settings {
            AppSettingsView()
                .preferredColorScheme(.dark)
        }
    }
}

/// Keeps the existing macOS behavior by default: closing the last window leaves
/// Glass TV available for a verified movie stream. The General setting makes the
/// alternate quit-on-close behavior explicit.
final class GlassTVApplicationDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        !UserDefaults.standard.bool(forKey: AppPreferences.keepRunningWhenWindowClosesKey)
    }
}

enum CaptureRunner {
    private static var path: String?
    static private(set) var captureSection: String?
    private static var selfTest = false

    static func configure(arguments: [String]) {
        VerificationRunner.configure(arguments: arguments)
        if let index = arguments.firstIndex(of: "--capture"), arguments.indices.contains(index + 1) {
            path = arguments[index + 1]
        }
        if let index = arguments.firstIndex(of: "--capture-section"), arguments.indices.contains(index + 1) {
            captureSection = arguments[index + 1]
        }
        selfTest = arguments.contains("--self-test")
        if selfTest {
            do { try ProtocolSelfTest.run(); print("Glass TV protocol self-test passed"); exit(EXIT_SUCCESS) }
            catch { fputs("Glass TV protocol self-test failed: \(error.localizedDescription)\n", stderr); exit(EXIT_FAILURE) }
        }
    }

    static func scheduleIfNeeded() {
        VerificationRunner.scheduleIfNeeded()
        guard let path else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            guard let window = NSApplication.shared.windows.first(where: { $0.contentView != nil }),
                  let content = window.contentView else { return }
            let bounds = content.bounds
            guard let rep = content.bitmapImageRepForCachingDisplay(in: bounds) else { return }
            content.cacheDisplay(in: bounds, to: rep)
            guard let png = rep.representation(using: .png, properties: [:]) else { return }
            do { try png.write(to: URL(fileURLWithPath: path), options: .atomic) }
            catch { fputs("Capture failed: \(error.localizedDescription)\n", stderr) }
            NSApplication.shared.terminate(nil)
        }
    }
}
