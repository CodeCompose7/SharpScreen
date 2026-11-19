import XCTest
@testable import SharpScreen

/// USB accessory link (issue #3): framing, transfer splitting, which tablet
/// USB serves, and what is remembered per tablet.
final class UsbLinkTests: XCTestCase {
    func testFrameRoundTrip() {
        var parser = UsbLink.Parser()
        let payload = Data((0..<300).map { UInt8($0 & 0xFF) })
        let bytes = UsbLink.encode(.data, session: 7, payload: payload)
        XCTAssertEqual(bytes.count, UsbLink.headerSize + 300)
        XCTAssertEqual(Array(bytes.prefix(4)), [3, 7, 0x01, 0x2C])
        XCTAssertEqual(parser.feed(bytes), [UsbLink.Frame(type: .data, session: 7, payload: payload)])
    }

    func testParserHandlesSplitAndBatchedInput() {
        var parser = UsbLink.Parser()
        let a = UsbLink.encode(.open, session: 1)
        let b = UsbLink.encode(.data, session: 1, payload: Data([9, 8, 7]))
        let c = UsbLink.encode(.close, session: 1)
        let stream = a + b + c
        var frames: [UsbLink.Frame] = []
        // One byte at a time: frames come out whole and in order.
        for byte in stream { frames += parser.feed(Data([byte])) }
        XCTAssertEqual(frames.map(\.type), [.open, .data, .close])
        XCTAssertEqual(frames[1].payload, Data([9, 8, 7]))
        // All at once.
        var batched = UsbLink.Parser()
        XCTAssertEqual(batched.feed(stream).count, 3)
    }

    func testParserSkipsUnknownFrameTypes() {
        var parser = UsbLink.Parser()
        let unknown = Data([42, 0, 0, 2, 1, 2])
        let hello = UsbLink.encode(.hello, session: 0, payload: Data([UsbLink.version]))
        XCTAssertEqual(parser.feed(unknown + hello).map(\.type), [.hello])
    }

    func testTransfersNeverEndOnAWholePacket() {
        XCTAssertEqual(UsbLink.transferSizes(length: 1024, maxPacket: 1024), [1023, 1])
        XCTAssertEqual(UsbLink.transferSizes(length: 16384, maxPacket: 1024), [16383, 1])
        XCTAssertEqual(UsbLink.transferSizes(length: 1500, maxPacket: 1024), [1500])
        XCTAssertEqual(UsbLink.transferSizes(length: 512, maxPacket: 1024), [512])
        XCTAssertEqual(UsbLink.transferSizes(length: 0, maxPacket: 1024), [])
        for length in [1, 4, 511, 512, 1023, 1024, 2048, 65539] {
            let sizes = UsbLink.transferSizes(length: length, maxPacket: 512)
            XCTAssertEqual(sizes.reduce(0, +), length)
            XCTAssertTrue(sizes.allSatisfy { $0 % 512 != 0 }, "\(length): \(sizes)")
        }
    }

    func testActiveTabletIsStickyThenPreferredThenFirst() {
        // The one streaming keeps USB while plugged in.
        XCTAssertEqual(UsbDeviceSelection.active(connected: ["A", "B"], current: "B", preferred: "A"), "B")
        // Otherwise the user's pick, otherwise the first one.
        XCTAssertEqual(UsbDeviceSelection.active(connected: ["A", "B"], current: nil, preferred: "B"), "B")
        XCTAssertEqual(UsbDeviceSelection.active(connected: ["A", "B"], current: "C", preferred: "D"), "A")
        XCTAssertNil(UsbDeviceSelection.active(connected: [], current: "A", preferred: "A"))
    }

    func testStoreRemembersDecisionsNamesAndPreference() {
        let suite = "UsbLinkTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UsbDeviceStore(defaults: defaults)
        XCTAssertNil(store.decision(for: "R54"))
        store.setDecision(.accessory, for: "R54")
        store.setDecision(.adbOnly, for: "XYZ")
        store.setName("Tab S11 Ultra", for: "R54")
        store.preferred = "R54"
        let reloaded = UsbDeviceStore(defaults: defaults)
        XCTAssertEqual(reloaded.decision(for: "R54"), .accessory)
        XCTAssertEqual(reloaded.decision(for: "XYZ"), .adbOnly)
        XCTAssertEqual(reloaded.name(for: "R54"), "Tab S11 Ultra")
        XCTAssertEqual(reloaded.decidedSerials, ["R54", "XYZ"])
        reloaded.forget("R54")
        XCTAssertNil(reloaded.decision(for: "R54"))
        XCTAssertNil(reloaded.name(for: "R54"))
        XCTAssertNil(reloaded.preferred)
    }

    func testAccessoryProductIDs() {
        XCTAssertTrue(AccessoryLink.isAccessory(vendor: 0x18D1, product: 0x2D00))
        XCTAssertTrue(AccessoryLink.isAccessory(vendor: 0x18D1, product: 0x2D01))
        XCTAssertFalse(AccessoryLink.isAccessory(vendor: 0x18D1, product: 0x4EE7))
        XCTAssertFalse(AccessoryLink.isAccessory(vendor: 0x04E8, product: 0x2D00))
    }

    func testAccessoryClientPorts() {
        XCTAssertFalse(AccessoryClientPorts.contains(65000))
        AccessoryClientPorts.insert(65000)
        XCTAssertTrue(AccessoryClientPorts.contains(65000))
        AccessoryClientPorts.remove(65000)
        XCTAssertFalse(AccessoryClientPorts.contains(65000))
    }
}
