import Foundation
import XCTest
@testable import SharpScreen

/// The Mac UI ships Localizable.strings and InfoPlist.strings for every
/// language in `languages` (copied into the app bundle by
/// scripts/build_mac.sh). Keys are the English source text; every key must
/// exist in every language with the same format specifiers, or that UI shows
/// English fallbacks or crashes on a bad format.
final class LocalizationTests: XCTestCase {
    static let languages = ["en", "ko", "ja", "de", "fr", "ru", "zh-Hans", "es"]
    private static let translations = languages.filter { $0 != "en" }

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

    func testEveryShippedLanguageIsChecked() throws {
        let dirs = try FileManager.default.contentsOfDirectory(atPath: Self.resources.path)
            .filter { $0.hasSuffix(".lproj") }
            .map { String($0.dropLast(".lproj".count)) }
        XCTAssertEqual(Set(dirs), Set(Self.languages))
    }

    func testEveryLanguageHasTheEnglishKeys() throws {
        let en = try table("en")
        for lang in Self.translations {
            let t = try table(lang)
            XCTAssertEqual(Set(en.keys).subtracting(t.keys).sorted(), [], "missing in \(lang)")
            XCTAssertEqual(Set(t.keys).subtracting(en.keys).sorted(), [], "in \(lang) but not in en")
        }
    }

    func testEnglishValuesAreTheirKeys() throws {
        for (key, value) in try table("en") {
            XCTAssertEqual(key, value)
        }
    }

    func testFormatSpecifiersMatch() throws {
        for lang in Self.translations {
            let t = try table(lang)
            for (key, value) in t where !value.isEmpty {
                XCTAssertEqual(specifiers(key), specifiers(value), "\(lang): specifiers differ for \(key)")
            }
            XCTAssertFalse(t.values.contains(where: { $0.trimmingCharacters(in: .whitespaces).isEmpty }), "\(lang) has an empty value")
        }
    }

    func testInfoPlistStringsMatch() throws {
        let en = Set(try table("en", "InfoPlist").keys)
        for lang in Self.translations {
            XCTAssertEqual(Set(try table(lang, "InfoPlist").keys), en, lang)
        }
    }

    func testAppLanguageCoversEveryTranslation() {
        XCTAssertEqual(Set(AppLanguage.languages.map(\.rawValue)), Set(Self.languages))
        XCTAssertEqual(AppLanguage.matching("zh-Hans"), .simplifiedChinese)
        XCTAssertEqual(AppLanguage.matching("zh-Hans-CN"), .simplifiedChinese)
        XCTAssertEqual(AppLanguage.matching("zh-Hant-TW"), nil)
        XCTAssertEqual(AppLanguage.matching("de-DE"), .german)
        XCTAssertEqual(AppLanguage.matching("es-419"), .spanish)
        XCTAssertEqual(AppLanguage.matching("ko"), .korean)
        XCTAssertEqual(AppLanguage.matching("pt-BR"), nil)
        for lang in AppLanguage.languages {
            XCTAssertEqual(AppLanguage.matching(lang.rawValue), lang)
        }
    }
}
