import XCTest
@testable import SharpScreen

final class TileBudgetTests: XCTestCase {
    private let ms: UInt64 = 1_000_000
    private let moving = 16 * 1024
    private let still = 128 * 1024

    private func budget(now: UInt64, last: UInt64) -> Int {
        TileRefiner.wirelessBudget(nowNs: now, lastNewFrameNs: last, moving: moving, still: still)
    }

    func testNoFrameYetUsesTheFullBudget() {
        XCTAssertEqual(budget(now: 5_000 * ms, last: 0), still)
    }

    func testJustAfterANewFrameTilesYield() {
        XCTAssertEqual(budget(now: 1_008 * ms, last: 1_000 * ms), moving)
        XCTAssertEqual(budget(now: 1_099 * ms, last: 1_000 * ms), moving)
    }

    func testStillForTheHoldTimeUsesTheFullBudget() {
        XCTAssertEqual(budget(now: 1_100 * ms, last: 1_000 * ms), still)
        XCTAssertEqual(budget(now: 3_000 * ms, last: 1_000 * ms), still)
    }

    func testAFrameStampedAfterNowCountsAsStill() {
        // The refiner read its clock before the sender stamped the frame.
        XCTAssertEqual(budget(now: 999 * ms, last: 1_000 * ms), still)
    }
}

final class TileSettleTests: XCTestCase {
    func testUSBSettlesSoonerThanWiFi() {
        XCTAssertEqual(TileRefiner.settleNs(overUSB: true), 40_000_000)
        XCTAssertEqual(TileRefiner.settleNs(overUSB: false), 100_000_000)
    }
}
