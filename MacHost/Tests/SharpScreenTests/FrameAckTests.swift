import XCTest
@testable import SharpScreen

/// Issue #9: video frames the client has acknowledged, and when the link
/// counts as backed up.
final class FrameAckTests: XCTestCase {
    typealias State = StreamingServer.FrameAckState

    func testNothingIsBackloggedUntilTheClientOptsIn() {
        var s = State()
        for i in 0..<10 { s.noteSent(atNs: UInt64(i) * 1_000_000) }
        XCTAssertFalse(s.backlogged(nowNs: 1_000_000_000, limitNs: 30_000_000))
    }

    func testOldestUnacknowledgedFrameDecides() {
        var s = State()
        s.enabled = true
        for i in 0..<5 { s.noteSent(atNs: 1_000_000_000 + UInt64(i) * 8_000_000) }
        // Frames 0 and 1 acknowledged; frame 2 sent at +16 ms.
        s.acknowledge(count14: 2)
        XCTAssertEqual(s.acked, 2)
        XCTAssertFalse(s.backlogged(nowNs: 1_000_000_000 + 40_000_000, limitNs: 30_000_000)) // waited 24 ms
        XCTAssertTrue(s.backlogged(nowNs: 1_000_000_000 + 50_000_000, limitNs: 30_000_000)) // waited 34 ms
        s.acknowledge(count14: 5)
        XCTAssertFalse(s.backlogged(nowNs: 2_000_000_000, limitNs: 30_000_000))
    }

    func testCountWrapsAt16384() {
        var s = State()
        s.enabled = true
        for i in 0..<16390 { s.noteSent(atNs: UInt64(i)) }
        // The client has 16388 frames: 16388 mod 16384 = 4.
        s.acknowledge(count14: 4)
        XCTAssertEqual(s.acked, 16388)
    }

    func testStaleAckNeverMovesBackwards() {
        var s = State()
        s.enabled = true
        for i in 0..<10 { s.noteSent(atNs: UInt64(i)) }
        s.acknowledge(count14: 8)
        s.acknowledge(count14: 6)
        XCTAssertEqual(s.acked, 8)
    }
}
