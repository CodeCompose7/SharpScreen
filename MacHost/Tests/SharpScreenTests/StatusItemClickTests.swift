import AppKit
import XCTest
@testable import SharpScreen

/// Issue #7: a plain click on the menu bar icon toggles the settings window;
/// a right click or Control-click opens the menu.
final class StatusItemClickTests: XCTestCase {
    private func mouse(_ type: NSEvent.EventType, _ flags: NSEvent.ModifierFlags = []) -> NSEvent? {
        NSEvent.mouseEvent(with: type, location: .zero, modifierFlags: flags, timestamp: 0,
                           windowNumber: 0, context: nil, eventNumber: 0, clickCount: 1, pressure: 1)
    }

    func testRightClickOpensTheMenu() {
        XCTAssertTrue(AppDelegate.isMenuClick(mouse(.rightMouseDown)))
        XCTAssertTrue(AppDelegate.isMenuClick(mouse(.rightMouseUp)))
    }

    func testControlClickOpensTheMenu() {
        XCTAssertTrue(AppDelegate.isMenuClick(mouse(.leftMouseDown, .control)))
        XCTAssertTrue(AppDelegate.isMenuClick(mouse(.leftMouseUp, .control)))
    }

    func testPlainClickTogglesSettings() {
        XCTAssertFalse(AppDelegate.isMenuClick(mouse(.leftMouseDown)))
        XCTAssertFalse(AppDelegate.isMenuClick(mouse(.leftMouseUp, .command)))
        XCTAssertFalse(AppDelegate.isMenuClick(nil))
    }
}
