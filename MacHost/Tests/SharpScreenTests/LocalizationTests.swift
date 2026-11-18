import Foundation
import XCTest

/// The Mac UI ships en and ko Localizable.strings (copied into the app bundle
/// by scripts/build_mac.sh). Keys are the English source text; every key must
/// exist in both languages with the same format specifiers, or a Korean UI
/// shows English fallbacks or crashes on a bad format.
final class LocalizationTests: XCTestCase {
    private static let resources = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // SharpScreenTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // MacHost
        .appendingPathComponent("Resources")

    private func table(_ lang: String, _ name: String = "Localizable") throws -> [String: String] {
        let url = Self.resources.appendingPathComponent("\(lang).lproj/\(name).strings")
        let dict = try XCTUnwrap(NSDictionary(contentsOf: url) as? [String: String], "unreadable \(url.path)")
        XCTAssertFalse(dict.isEmpty, "\(url.path) is empty")
        return dict
    }

    private func specifiers(_ s: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "%(?:\\d+\\$)?[-+ #0']*\\d*(?:\\.\\d+)?(?:hh|h|ll|l|q|z|t|j)?[@dDuUxXoOfeEgGcCsSpaA%]")
        let ns = s as NSString
        return regex.matches(in: s, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range) }
            .filter { $0 != "%%" }
    }

    func testEnglishAndKoreanHaveTheSameKeys() throws {
        let en = try table("en"), ko = try table("ko")
        XCTAssertEqual(Set(en.keys).subtracting(ko.keys).sorted(), [], "missing in ko")
        XCTAssertEqual(Set(ko.keys).subtracting(en.keys).sorted(), [], "missing in en")
    }

    func testEnglishValuesAreTheirKeys() throws {
        for (key, value) in try table("en") {
            XCTAssertEqual(key, value)
        }
    }

    func testFormatSpecifiersMatch() throws {
        let ko = try table("ko")
        for (key, value) in ko where !value.isEmpty {
            XCTAssertEqual(specifiers(key), specifiers(value), "specifiers differ for \(key)")
        }
        XCTAssertFalse(ko.values.contains(where: { $0.trimmingCharacters(in: .whitespaces).isEmpty }))
    }

    func testInfoPlistStringsMatch() throws {
        XCTAssertEqual(Set(try table("en", "InfoPlist").keys), Set(try table("ko", "InfoPlist").keys))
    }
}
