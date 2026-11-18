import XCTest
import CoreMedia
import CoreVideo
@testable import SharpScreen

final class FramePacerTests: XCTestCase {
    private func makeBuffer() -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, 64, 64, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, nil, &buffer)
        return buffer!
    }

    func testRepeatsOnlyAfterMostOfAnInterval() {
        let policy = FramePacer.Policy(fps: 120)  // 8_333_333 ns
        XCTAssertEqual(policy.intervalNs, 8_333_333)
        XCTAssertFalse(policy.shouldRepeat(nowNs: 10_000_000, lastSubmitNs: 0), "no frame yet")
        XCTAssertFalse(policy.shouldRepeat(nowNs: 1_000_000 + 6_000_000, lastSubmitNs: 1_000_000))
        XCTAssertTrue(policy.shouldRepeat(nowNs: 1_000_000 + 6_300_000, lastSubmitNs: 1_000_000))
        XCTAssertFalse(policy.shouldRepeat(nowNs: 1_000_000, lastSubmitNs: 2_000_000), "clock went backwards")
    }

    func testTimestampsNeverGoBackwards() {
        XCTAssertEqual(FramePacer.Policy.monotonicUs(candidateUs: 100, lastUs: 50), 100)
        XCTAssertEqual(FramePacer.Policy.monotonicUs(candidateUs: 50, lastUs: 50), 51)
        XCTAssertEqual(FramePacer.Policy.monotonicUs(candidateUs: 40, lastUs: 50), 51)
    }

    func testReplayBeforeAnyFrameSubmitsNothing() {
        let pacer = FramePacer(fps: 120, encodeQueue: DispatchQueue(label: "t"), pacingEnabled: false) { _, _ in
            XCTFail("nothing to encode")
        }
        XCTAssertFalse(pacer.submit(nil, kind: .replay))
    }

    func testAtMostTwoFramesInFlightAndPtsIncrease() {
        let gate = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var stamps: [CMTime] = []
        let pacer = FramePacer(fps: 120, encodeQueue: DispatchQueue(label: "t"), pacingEnabled: false) { _, pts in
            gate.wait()
            lock.lock(); stamps.append(pts); lock.unlock()
        }
        let frame = makeBuffer()
        XCTAssertTrue(pacer.submit(frame, kind: .captured))
        XCTAssertTrue(pacer.submit(nil, kind: .idle))
        XCTAssertFalse(pacer.submit(frame, kind: .captured), "third while two are in flight")
        XCTAssertNotNil(pacer.lastBuffer, "a skipped frame still becomes the cached frame")
        gate.signal(); gate.signal()
        let done = expectation(description: "drained")
        DispatchQueue.global().async {
            while true { lock.lock(); let n = stamps.count; lock.unlock(); if n == 2 { break }; usleep(1000) }
            done.fulfill()
        }
        wait(for: [done], timeout: 2)
        XCTAssertTrue(pacer.submit(nil, kind: .repeated), "room again after draining")
        gate.signal()
        XCTAssertLessThan(stamps[0], stamps[1])
    }

    func testPacingFillsAStillScreenTowardTheFrameRate() {
        let lock = NSLock()
        var encoded = 0
        let pacer = FramePacer(fps: 120, encodeQueue: DispatchQueue(label: "t"), pacingEnabled: true) { _, _ in
            lock.lock(); encoded += 1; lock.unlock()
        }
        pacer.submit(makeBuffer(), kind: .captured)  // one frame, then the screen stays still
        pacer.start()
        Thread.sleep(forTimeInterval: 0.5)
        pacer.stop()
        lock.lock(); let n = encoded; lock.unlock()
        // 0.5 s at 120 fps = 60 slots; allow scheduler slack on a busy test host.
        XCTAssertGreaterThan(n, 45)
        XCTAssertLessThanOrEqual(n, 62)
    }
}
