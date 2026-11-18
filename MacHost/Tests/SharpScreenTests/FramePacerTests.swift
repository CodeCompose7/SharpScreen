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

    func testRepeatsOnlyWhenTheLastSlotWasEmpty() {
        XCTAssertEqual(FramePacer.Policy(fps: 120).intervalNs, 8_333_333)
        let previousTick: UInt64 = 100_000_000
        XCTAssertFalse(FramePacer.Policy.shouldRepeat(lastSubmitNs: 0, previousTickNs: previousTick), "no frame yet")
        XCTAssertTrue(FramePacer.Policy.shouldRepeat(lastSubmitNs: 95_000_000, previousTickNs: previousTick),
                      "nothing since the previous tick")
        XCTAssertFalse(FramePacer.Policy.shouldRepeat(lastSubmitNs: 100_000_001, previousTickNs: previousTick),
                       "a capture frame already filled this slot")
    }

    func testTimestampsNeverGoBackwards() {
        XCTAssertEqual(FramePacer.Policy.monotonicUs(candidateUs: 100, lastUs: 50), 100)
        XCTAssertEqual(FramePacer.Policy.monotonicUs(candidateUs: 50, lastUs: 50), 51)
        XCTAssertEqual(FramePacer.Policy.monotonicUs(candidateUs: 40, lastUs: 50), 51)
    }

    func testReplayBeforeAnyFrameSubmitsNothing() {
        let pacer = FramePacer(fps: 120, encodeQueue: DispatchQueue(label: "t"), pacingEnabled: false) { _, _, _, _ in
            XCTFail("nothing to encode")
        }
        XCTAssertFalse(pacer.submit(nil, kind: .replay))
    }

    func testAtMostTwoFramesInFlightAndPtsIncrease() {
        let gate = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var stamps: [CMTime] = []
        let pacer = FramePacer(fps: 120, encodeQueue: DispatchQueue(label: "t"), pacingEnabled: false) { _, pts, _, _ in
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
        let pacer = FramePacer(fps: 120, encodeQueue: DispatchQueue(label: "t"), pacingEnabled: true) { _, _, _, _ in
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

    /// Capture frames arriving at an irregular ~100 fps plus the pacer must
    /// not exceed one frame per slot by much (it ran at 145-153 fps before).
    func testIrregularCaptureFramesDoNotOvershootTheRate() {
        let lock = NSLock()
        var encoded = 0
        let pacer = FramePacer(fps: 120, encodeQueue: DispatchQueue(label: "t"), pacingEnabled: true) { _, _, _, _ in
            lock.lock(); encoded += 1; lock.unlock()
        }
        let frame = makeBuffer()
        pacer.submit(frame, kind: .captured)
        pacer.start()
        let end = Date().addingTimeInterval(1.0)
        var i = 0
        while Date() < end {
            pacer.submit(frame, kind: .captured)
            pacer.submit(nil, kind: .idle)  // ignored while pacing
            usleep(useconds_t([7_000, 13_000, 9_000, 11_000][i % 4]))  // mean 10 ms
            i += 1
        }
        pacer.stop()
        lock.lock(); let n = encoded; lock.unlock()
        XCTAssertGreaterThan(n, 105)
        XCTAssertLessThan(n, 135, "one frame per 1/120 s slot, plus capture frames sharing a slot")
    }
}
