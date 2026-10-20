import Foundation

enum AppPreferences {
    static let keepRunningWhenWindowClosesKey = "glassTV.keepRunningWhenWindowCloses"

    static func registerDefaults() {
        UserDefaults.standard.register(defaults: [
            keepRunningWhenWindowClosesKey: true
        ])
    }
}
