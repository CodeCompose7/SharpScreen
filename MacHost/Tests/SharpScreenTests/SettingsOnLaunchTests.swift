import XCTest
@testable import SharpScreen

final class SettingsOnLaunchTests: XCTestCase {
    func testOptionOnShowsSettings() {
        XCTAssertTrue(AppDelegate.showsSettingsAtLaunch(option: true, launchAtLogin: false))
    }

    func testOptionOffStartsInTheMenuBar() {
        XCTAssertFalse(AppDelegate.showsSettingsAtLaunch(option: false, launchAtLogin: false))
    }

    func testLaunchAtLoginStaysInTheBackground() {
        XCTAssertFalse(AppDelegate.showsSettingsAtLaunch(option: true, launchAtLogin: true))
        XCTAssertFalse(AppDelegate.showsSettingsAtLaunch(option: false, launchAtLogin: true))
    }
}
