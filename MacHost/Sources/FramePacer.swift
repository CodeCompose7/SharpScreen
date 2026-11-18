import Foundation
import CoreMedia
import CoreVideo
import os

/// Keeps the stream at the configured frame rate.
///
/// ScreenCaptureKit only delivers new pixels when the screen changed (plus
/// ~72 "idle" ticks a second on a still screen), so a 120 Hz stream ran at
/// 70-110 fps depending on content. With pacing on, the timer owns the
/// cadence: one frame per 1/fps slot. New pixels are still encoded the moment
/// they arrive (no added latency); a tick re-encodes the last frame only if
/// nothing went out since the previous tick, and idle ticks are dropped.
/// An unchanged frame costs a few hundred bytes of HEVC.
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
        var idleIgnored = 0
        /// Captured frames whose capture time is not after the previous one's.
        var outOfOrder = 0

        var encoded: Int { captured + idle + repeated + replay }
    }

    /// Pure timing rules, separated for tests.
    struct Policy {
        let intervalNs: UInt64

        init(fps: Int) {
            intervalNs = UInt64(1_000_000_000 / max(1, fps))
        }

        /// One frame per slot: repeat at a tick only if nothing was submitted
        /// since the previous tick. A gap-based rule ("nothing for 75% of an
        /// interval") let a repeat and an irregular capture frame share a slot
        /// and ran a 120 fps stream at 145-153 fps during motion.
        static func shouldRepeat(lastSubmitNs: UInt64, previousTickNs: UInt64) -> Bool {
            lastSubmitNs > 0 && lastSubmitNs <= previousTickNs
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

    /// diag.noRepeats: encode only new pixels. No pacing timer, and
    /// ScreenCaptureKit's idle ticks are dropped too; keepalive and keyframe
    /// replays still go out.
    static var noRepeats: Bool {
        UserDefaults.standard.bool(forKey: "diag.noRepeats")
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
        var lastCapturePts: CMTime = .invalid
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    private let encodeQueue: DispatchQueue
    private let encode: (CVPixelBuffer, CMTime, Bool) -> Void
    private var timer: DispatchSourceTimer?
    private var statsTimer: DispatchSourceTimer?
    private var previousTickNs: UInt64 = 0  // touched only on the timer queue
    private let policy: Policy
    let pacingEnabled: Bool

    init(fps: Int, encodeQueue: DispatchQueue, pacingEnabled: Bool = FramePacer.isEnabled,
         encode: @escaping (CVPixelBuffer, CMTime, Bool) -> Void) {
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
    func submit(_ buffer: CVPixelBuffer?, kind: Kind, capturePts: CMTime = .invalid) -> Bool {
        if capturePts.isValid {
            state.withLock { s in
                if s.lastCapturePts.isValid, CMTimeCompare(capturePts, s.lastCapturePts) <= 0 {
                    s.counters.outOfOrder += 1
                } else {
                    s.lastCapturePts = capturePts
                }
            }
        }
        // The timer fills still screens; ScreenCaptureKit's idle ticks would
        // only crowd the slots.
        if kind == .idle && (Self.noRepeats || (pacingEnabled && timer != nil)) {
            state.withLock { $0.counters.idleIgnored += 1 }
            return false
        }
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let job: (CVPixelBuffer, CMTime, Bool)? = state.withLockUnchecked { s in
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
            return (frame, CMTime(value: us, timescale: 1_000_000), kind != .captured)
        }
        guard let (frame, pts, isRepeat) = job else { return false }
        encodeQueue.async { [encode, state] in
            encode(frame, pts, isRepeat)
            state.withLock { $0.pending -= 1 }
        }
        return true
    }

    /// Start the gap-filling timer (no-op when pacing is disabled).
    func start() {
        stop()
        let startNs = DispatchTime.now().uptimeNanoseconds
        state.withLock { $0.statsStartNs = startNs }
        guard pacingEnabled, !Self.noRepeats else {
            // No timer, so log the per-second counters from a slow one.
            let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "FramePacer.stats"))
            t.schedule(deadline: .now() + 1, repeating: 1)
            t.setEventHandler { [weak self] in self?.logStatsIfDue(nowNs: DispatchTime.now().uptimeNanoseconds) }
            t.resume()
            statsTimer = t
            return
        }
        previousTickNs = startNs
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
        statsTimer?.cancel()
        statsTimer = nil
    }

    private func tick() {
        let due = state.withLock { Policy.shouldRepeat(lastSubmitNs: $0.lastSubmitNs, previousTickNs: previousTickNs) }
        if due { submit(nil, kind: .repeated) }
        // Mark the slot boundary after our own repeat, so it does not count as
        // "already filled" for the next slot.
        let nowNs = DispatchTime.now().uptimeNanoseconds
        previousTickNs = nowNs
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
        debugLog("Capture: \(c.encoded) encoded = \(c.captured) new + \(c.idle) idle + \(c.repeated) repeated + \(c.replay) replay; \(c.skippedBusy) skipped (encoder busy), \(c.idleIgnored) idle ticks ignored, \(c.outOfOrder) out of order")
    }
}
