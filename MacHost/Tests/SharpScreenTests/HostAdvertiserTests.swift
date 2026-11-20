import XCTest
@testable import SharpScreen

final class HostAdvertiserTests: XCTestCase {
    private let token = Data((0..<32).map { UInt8($0) })

    /// The tablet's HostFinder checks the same vector (HostProofTest).
    func testProofIsStable() {
        XCTAssertEqual(HostAdvertiser.proof(token: token, ip: "192.168.0.12", port: 54321), HostAdvertiserTests.expected)
    }

    func testProofDependsOnAddressPortAndToken() {
        let base = HostAdvertiser.proof(token: token, ip: "192.168.0.12", port: 54321)
        XCTAssertNotEqual(base, HostAdvertiser.proof(token: token, ip: "192.168.0.13", port: 54321))
        XCTAssertNotEqual(base, HostAdvertiser.proof(token: token, ip: "192.168.0.12", port: 54322))
        XCTAssertNotEqual(base, HostAdvertiser.proof(token: Data(repeating: 7, count: 32), ip: "192.168.0.12", port: 54321))
        XCTAssertEqual(base.count, 16)
    }

    func testTxtRecordLayout() {
        let txt = HostAdvertiser.txtRecord(token: token, port: 54321, addresses: ["192.168.0.12", "100.119.13.7"])
        var entries: [String] = []
        var i = 0
        while i < txt.count {
            let len = Int(txt[i]); i += 1
            entries.append(String(decoding: txt[i..<(i + len)], as: UTF8.self)); i += len
        }
        XCTAssertEqual(entries.first, "v=1")
        XCTAssertEqual(entries.last, "a=192.168.0.12=\(HostAdvertiserTests.expected),100.119.13.7=\(HostAdvertiser.proof(token: token, ip: "100.119.13.7", port: 54321))")
    }

    static let expected = "UWJFtLLQBlzfU1zu"
}
