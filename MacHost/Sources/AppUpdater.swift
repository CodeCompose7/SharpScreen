import Cocoa
import Combine
import Sparkle

/// Sparkle auto-update, one instance for the whole app. Sparkle checks the
/// appcast once a day (SUScheduledCheckInterval) and asks before downloading;
/// nothing is installed without the user clicking through the dialog. The
/// appcast is attached to each GitHub Release (scripts/make_appcast.sh) and
/// every download is checked against SUPublicEDKey.
/// Builds without SUFeedURL in Info.plist (swift run, tests) never start it.
/// diag.updateFeedURL points a build at another appcast, to test an update.
final class AppUpdater: NSObject, ObservableObject, NSMenuItemValidation, SPUUpdaterDelegate {
    static let shared = AppUpdater()

    @Published private(set) var canCheckForUpdates = false

    private var controller: SPUStandardUpdaterController!
    private var observation: AnyCancellable?

    private override init() {
        super.init()
        controller = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: self, userDriverDelegate: nil)
        guard Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") != nil else {
            debugLog("Sparkle: no SUFeedURL in Info.plist — update checks disabled (dev build)")
            return
        }
        controller.startUpdater()
        observation = controller.updater.publisher(for: \.canCheckForUpdates)
            .receive(on: DispatchQueue.main)
            .assign(to: \.canCheckForUpdates, on: self)
    }

    @objc func checkForUpdates(_ sender: Any?) {
        controller.checkForUpdates(sender)
    }

    func feedURLString(for updater: SPUUpdater) -> String? {
        UserDefaults.standard.string(forKey: "diag.updateFeedURL")
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        canCheckForUpdates
    }
}

enum AppInfo {
    static let version: String =
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"

    /// The project SharpScreen was forked from (MIT).
    static let upstream = URL(string: "https://github.com/tranvuongquocdat/SideScreen")!
    static let github = URL(string: "https://github.com/CodeCompose7/SharpScreen")!
    static let releases = URL(string: "https://github.com/CodeCompose7/SharpScreen/releases")!
    static let reportProblem = URL(string: "https://github.com/CodeCompose7/SharpScreen/issues")!
}
