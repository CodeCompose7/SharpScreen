import Foundation

/// The app's own language choice (General ▸ Language). Stored the standard
/// macOS way, as this app's `AppleLanguages`, which is also what System
/// Settings ▸ Language & Region ▸ Applications writes; takes effect when the
/// app next launches.
enum AppLanguage: String, CaseIterable, Hashable {
    case system
    case english = "en"
    case korean = "ko"
    case japanese = "ja"
    case german = "de"
    case french = "fr"
    case russian = "ru"
    case simplifiedChinese = "zh-Hans"
    case spanish = "es"

    private static let key = "AppleLanguages"

    /// Every language the app ships, in picker order.
    static let languages: [AppLanguage] = allCases.filter { $0 != .system }

    /// The language's name in that language (picker label; never translated).
    var nativeName: String {
        switch self {
        case .system: return "System"
        case .english: return "English"
        case .korean: return "한국어"
        case .japanese: return "日本語"
        case .german: return "Deutsch"
        case .french: return "Français"
        case .russian: return "Русский"
        case .simplifiedChinese: return "简体中文"
        case .spanish: return "Español"
        }
    }

    /// Maps an `AppleLanguages` entry ("de-DE", "zh-Hans-CN", "es-419", …) to
    /// a shipped language, or nil. Traditional Chinese is not shipped.
    static func matching(_ identifier: String) -> AppLanguage? {
        let id = identifier.replacingOccurrences(of: "_", with: "-")
        if id.hasPrefix("zh-Hans") || id == "zh-CN" || id == "zh-SG" { return .simplifiedChinese }
        let code = id.split(separator: "-").first.map(String.init) ?? id
        return languages.first { $0 != .simplifiedChinese && $0.rawValue == code }
    }

    /// The choice saved for this app (not the language in use right now).
    static var saved: AppLanguage {
        let domain = UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "")
        guard let first = (domain?[key] as? [String])?.first else { return .system }
        return matching(first) ?? .system
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
