import Foundation
import CoreMedia
import CoreVideo
import os

/// Keeps the stream at the configured frame rate.
///
/// ScreenCaptureKit only delivers a frame when the screen changed, so the
/// stream ran at 70-110 fps on a 120 Hz setting depending on what was on
/// screen. New frames are still encoded the moment they arrive (no added
/// latency); the pacer only fills the gaps by re-encoding the last frame when
/// nothing went out for most of a frame interval. An unchanged frame costs a
/// few hundred bytes of HEVC.
///
/// All submissions go through `submit`, which also keeps presentation
/// timestamps strictly increasing: a repeat is stamped "now", and a capture
/// frame delivered after it may carry an earlier capture time.
final class FramePacer {
    enum Kind {
        case captured   // new pixels from ScreenCaptureKit
        case idle       // ScreenCaptureKit tick without new pixels
        case repeated   // pacer filled a gap
        case replay     // keepalive / keyframe replay of the cached frame
    }

    struct Counters: Equatable {
        var captured = 0
        var idle = 0
        var repeated = 0
        var replay = 0
        var skippedBusy = 0

        var encoded: Int { captured + idle + repeated + replay }
    }

    /// Pure timing rules, separated for tests.
    struct Policy {
        let intervalNs: UInt64

        /// Repeat once nothing was submitted for this share of an interval.
        /// Below 1 so timer jitter does not skip a slot; above 0.5 so a repeat
        /// never lands right next to a fresh capture frame.
        static let gapFraction = 0.75

        init(fps: Int) {
            intervalNs = UInt64(1_000_000_000 / max(1, fps))
        }

        func shouldRepeat(nowNs: UInt64, lastSubmitNs: UInt64) -> Bool {
            guard lastSubmitNs > 0, nowNs > lastSubmitNs else { return false }
            return Double(nowNs - lastSubmitNs) >= Double(intervalNs) * Self.gapFraction
        }

        /// Next timestamp in microseconds, strictly after `lastUs`.
        static func monotonicUs(candidateUs: Int64, lastUs: Int64) -> Int64 {
            candidateUs > lastUs ? candidateUs : lastUs + 1
        }
    }

    /// UserDefaults switch; on unless set to false.
    static let enabledKey = "constantFrameRate"
    static var isEnabled: Bool {
        UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true
    }

    /// Frames allowed in flight on the encode queue (same limit as before).
    static let maxPendingEncodes: Int32 = 2

    private struct State {
        var lastBuffer: CVPixelBuffer?
        var lastSubmitNs: UInt64 = 0
        var lastPtsUs: Int64 = 0
        var counters = Counters()
        var statsStartNs: UInt64 = 0
        var pending: Int32 = 0
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    private let encodeQueue: DispatchQueue
    private let encode: (CVPixelBuffer, CMTime) -> Void
    private var timer: DispatchSourceTimer?
    private let policy: Policy
    let pacingEnabled: Bool

    init(fps: Int, encodeQueue: DispatchQueue, pacingEnabled: Bool = FramePacer.isEnabled,
         encode: @escaping (CVPixelBuffer, CMTime) -> Void) {
        self.policy = Policy(fps: fps)
        self.encodeQueue = encodeQueue
        self.pacingEnabled = pacingEnabled
        self.encode = encode
    }

    deinit { stop() }

    /// The most recent frame, for keepalives and keyframe replays.
    // CVPixelBuffer is not Sendable; the lock is what serializes access.
    var lastBuffer: CVPixelBuffer? { state.withLockUnchecked { $0.lastBuffer } }

    /// Submit a frame for encoding. `buffer` nil means "the cached frame".
    /// Returns false when nothing was submitted (no frame yet, or busy).
    @discardableResult
    func submit(_ buffer: CVPixelBuffer?, kind: Kind) -> Bool {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let job: (CVPixelBuffer, CMTime)? = state.withLockUnchecked { s in
            if let buffer { s.lastBuffer = buffer }
            guard s.pending < Self.maxPendingEncodes else {
                s.counters.skippedBusy += 1
                return nil
            }
            guard let frame = buffer ?? s.lastBuffer else { return nil }
            s.pending += 1
            let us = Policy.monotonicUs(candidateUs: Int64(nowNs / 1000), lastUs: s.lastPtsUs)
            s.lastPtsUs = us
            s.lastSubmitNs = nowNs
            switch kind {
            case .captured: s.counters.captured += 1
            case .idle: s.counters.idle += 1
            case .repeated: s.counters.repeated += 1
            case .replay: s.counters.replay += 1
            }
            return (frame, CMTime(value: us, timescale: 1_000_000))
        }
        guard let (frame, pts) = job else { return false }
        encodeQueue.async { [encode, state] in
            encode(frame, pts)
            state.withLock { $0.pending -= 1 }
        }
        return true
    }

    /// Start the gap-filling timer (no-op when pacing is disabled).
    func start() {
        stop()
        state.withLock { $0.statsStartNs = DispatchTime.now().uptimeNanoseconds }
        guard pacingEnabled else { return }
        let t = DispatchSource.makeTimerSource(flags: .strict, queue: DispatchQueue(label: "FramePacer", qos: .userInteractive))
        t.schedule(deadline: .now() + .nanoseconds(Int(policy.intervalNs)),
                   repeating: .nanoseconds(Int(policy.intervalNs)), leeway: .nanoseconds(0))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    private func tick() {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let due = state.withLock { policy.shouldRepeat(nowNs: nowNs, lastSubmitNs: $0.lastSubmitNs) }
        if due { submit(nil, kind: .repeated) }
        logStatsIfDue(nowNs: nowNs)
    }

    /// One line per second: why the stream ran at the rate it did.
    private func logStatsIfDue(nowNs: UInt64) {
        let snapshot: Counters? = state.withLock { s in
            guard nowNs - s.statsStartNs >= 1_000_000_000 else { return nil }
            let c = s.counters
            s.counters = Counters()
            s.statsStartNs = nowNs
            return c
        }
        guard let c = snapshot else { return }
        debugLog("Capture: \(c.encoded) encoded = \(c.captured) new + \(c.idle) idle + \(c.repeated) repeated + \(c.replay) replay; \(c.skippedBusy) skipped (encoder busy)")
    }
}
