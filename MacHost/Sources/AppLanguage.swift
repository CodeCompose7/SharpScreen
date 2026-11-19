import Foundation

/// The app's own language choice (General ▸ Language). Stored the standard
/// macOS way, as this app's `AppleLanguages`, which is also what System
/// Settings ▸ Language & Region ▸ Applications writes; takes effect when the
/// app next launches.
enum AppLanguage: String, CaseIterable, Hashable {
    case system
    case english = "en"
    case korean = "ko"

    private static let key = "AppleLanguages"

    /// The choice saved for this app (not the language in use right now).
    static var saved: AppLanguage {
        let domain = UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "")
        guard let first = (domain?[key] as? [String])?.first else { return .system }
        if first.hasPrefix("ko") { return .korean }
        if first.hasPrefix("en") { return .english }
        return .system
    }

    /// The choice as it was when this process started; the UI is in that
    /// language until a restart. Read once early in launch.
    static let atLaunch = saved

    func save() {
        if self == .system {
            UserDefaults.standard.removeObject(forKey: Self.key)
        } else {
            UserDefaults.standard.set([rawValue], forKey: Self.key)
        }
    }
}
