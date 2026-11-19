import Foundation
import os

/// Where a frame's time goes, from its content time on the Mac to the vsync
/// that shows it on the tablet (issue #13).
///
/// The content time is the frame's presentation time on the Mac's virtual
/// display (ScreenCaptureKit's PTS): when the Mac itself shows those pixels,
/// up to one refresh after they were captured. So the total is how far the
/// tablet runs behind the Mac's own screen, and the first stage (content time
/// to encode start) is often negative.
///
/// The Mac keeps, for each new frame, when it started and finished encoding
/// and when it handed the frame to the socket. A tablet launched with
/// `--ez diag.frameTiming true` reports, per frame drawn, how long the frame
/// spent from arrival to decoder output, from there to its draw, and from the
/// draw to its vsync. Both sides only measure intervals on their own clock; the
/// network share is the round trip (send to the report's arrival, minus the
/// tablet's part before it reported) split in half.
final class LatencyBreakdown {
    struct SendRecord: Equatable {
        let contentNs: UInt64
        let encodeStartNs: UInt64
        let encodeDoneNs: UInt64
        let sendNs: UInt64
    }

    struct Report: Equatable {
        let contentNs: UInt64
        let decodeUs: UInt32
        let waitUs: UInt32
        let presentUs: UInt32
    }

    /// One frame's stages, in ms.
    struct Sample: Equatable {
        let captureToEncode: Double
        let encode: Double
        let toSocket: Double
        let network: Double
        let decode: Double
        let drawWait: Double
        let toVsync: Double

        var total: Double { captureToEncode + encode + toSocket + network + decode + drawWait + toVsync }
    }

    static func sample(_ record: SendRecord, _ report: Report, receivedNs: UInt64) -> Sample? {
        guard record.encodeDoneNs >= record.encodeStartNs,
              record.sendNs >= record.encodeDoneNs,
              receivedNs >= record.sendNs else { return nil }
        let tabletBeforeReportNs = (UInt64(report.decodeUs) + UInt64(report.waitUs)) * 1000
        let roundTripNs = receivedNs - record.sendNs
        guard roundTripNs >= tabletBeforeReportNs else { return nil }
        let ms = { (ns: UInt64) in Double(ns) / 1e6 }
        return Sample(
            captureToEncode: (Double(record.encodeStartNs) - Double(record.contentNs)) / 1e6,
            encode: ms(record.encodeDoneNs - record.encodeStartNs),
            toSocket: ms(record.sendNs - record.encodeDoneNs),
            network: ms((roundTripNs - tabletBeforeReportNs) / 2),
            decode: Double(report.decodeUs) / 1000,
            drawWait: Double(report.waitUs) / 1000,
            toVsync: Double(report.presentUs) / 1000
        )
    }

    private struct State {
        var records: [UInt64: SendRecord] = [:]
        var order: [UInt64] = []
        var samples: [Sample] = []
        var unmatched = 0
        var windowStartNs: UInt64 = 0
    }

    private static let keep = 512
    static let windowNs: UInt64 = 2_000_000_000

    private let state = OSAllocatedUnfairLock(initialState: State())

    /// Keeps the first send of each content time: a repeat re-sends the same
    /// pixels later, and the tablet reports the first copy it drew.
    func recordSend(_ record: SendRecord) {
        state.withLock { s in
            guard s.records[record.contentNs] == nil else { return }
            s.order.append(record.contentNs)
            s.records[record.contentNs] = record
            if s.order.count > Self.keep {
                let drop = s.order.count - Self.keep
                for key in s.order.prefix(drop) { s.records.removeValue(forKey: key) }
                s.order.removeFirst(drop)
            }
        }
    }

    /// Takes a tablet report; returns a summary line once per window.
    func recordReport(_ report: Report, receivedNs: UInt64) -> String? {
        state.withLock { s -> String? in
            if s.windowStartNs == 0 { s.windowStartNs = receivedNs }
            if let record = s.records[report.contentNs],
               let sample = Self.sample(record, report, receivedNs: receivedNs) {
                s.samples.append(sample)
            } else {
                s.unmatched += 1
            }
            guard receivedNs - s.windowStartNs >= Self.windowNs, !s.samples.isEmpty else { return nil }
            let line = Self.summary(s.samples, unmatched: s.unmatched)
            s.samples.removeAll(keepingCapacity: true)
            s.unmatched = 0
            s.windowStartNs = receivedNs
            return line
        }
    }

    static func summary(_ samples: [Sample], unmatched: Int) -> String {
        func stat(_ values: [Double]) -> String {
            let sorted = values.sorted()
            let p = { (q: Double) in sorted[min(sorted.count - 1, Int(Double(sorted.count - 1) * q))] }
            return String(format: "%.1f/%.1f/%.1f", p(0.5), p(0.9), sorted.last ?? 0)
        }
        let parts: [(String, (Sample) -> Double)] = [
            ("total", { $0.total }),
            ("mac>encode", { $0.captureToEncode }),
            ("encode", { $0.encode }),
            (">socket", { $0.toSocket }),
            ("network", { $0.network }),
            ("decode", { $0.decode }),
            ("draw wait", { $0.drawWait }),
            (">vsync", { $0.toVsync }),
        ]
        let body = parts.map { "\($0.0) \(stat(samples.map($0.1)))" }.joined(separator: ", ")
        return "Latency ms p50/p90/max (\(samples.count) frames\(unmatched > 0 ? ", \(unmatched) unmatched" : "")): \(body)"
    }
}
