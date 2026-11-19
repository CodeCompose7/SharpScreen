import XCTest
@testable import SharpScreen

final class LatencyBreakdownTests: XCTestCase {
    private let ms: UInt64 = 1_000_000

    func testSampleSplitsTheRoundTrip() throws {
        // Composited at 100 ms, encode 102-108, socket at 109. The tablet spent
        // 7 ms decoding and 1 ms before its draw; the report came back at 125:
        // 16 ms after the send, 8 of them on the tablet, so 4 ms each way.
        let record = LatencyBreakdown.SendRecord(contentNs: 100 * ms, encodeStartNs: 102 * ms, encodeDoneNs: 108 * ms, sendNs: 109 * ms)
        let report = LatencyBreakdown.Report(contentNs: 100 * ms, decodeUs: 7_000, waitUs: 1_000, presentUs: 5_000)
        let s = try XCTUnwrap(LatencyBreakdown.sample(record, report, receivedNs: 125 * ms))
        XCTAssertEqual(s.captureToEncode, 2, accuracy: 0.001)
        XCTAssertEqual(s.encode, 6, accuracy: 0.001)
        XCTAssertEqual(s.toSocket, 1, accuracy: 0.001)
        XCTAssertEqual(s.network, 4, accuracy: 0.001)
        XCTAssertEqual(s.decode, 7, accuracy: 0.001)
        XCTAssertEqual(s.drawWait, 1, accuracy: 0.001)
        XCTAssertEqual(s.toVsync, 5, accuracy: 0.001)
        XCTAssertEqual(s.total, 26, accuracy: 0.001)
    }

    func testEncodingBeforeTheContentTimeIsNegative() throws {
        // The content time is the Mac display's vsync for the frame, which the
        // encoder can start before.
        let record = LatencyBreakdown.SendRecord(contentNs: 100 * ms, encodeStartNs: 96 * ms, encodeDoneNs: 102 * ms, sendNs: 102 * ms)
        let report = LatencyBreakdown.Report(contentNs: 100 * ms, decodeUs: 7_000, waitUs: 0, presentUs: 10_000)
        let s = try XCTUnwrap(LatencyBreakdown.sample(record, report, receivedNs: 117 * ms))
        XCTAssertEqual(s.captureToEncode, -4, accuracy: 0.001)
        XCTAssertEqual(s.network, 4, accuracy: 0.001)
        XCTAssertEqual(s.total, 23, accuracy: 0.001)
    }

    func testImpossibleTimesAreDropped() {
        let record = LatencyBreakdown.SendRecord(contentNs: 100 * ms, encodeStartNs: 102 * ms, encodeDoneNs: 108 * ms, sendNs: 109 * ms)
        // The tablet claims more time than the whole round trip.
        let report = LatencyBreakdown.Report(contentNs: 100 * ms, decodeUs: 20_000, waitUs: 0, presentUs: 0)
        XCTAssertNil(LatencyBreakdown.sample(record, report, receivedNs: 115 * ms))
    }

    func testReportsSummarizeOncePerWindow() {
        let breakdown = LatencyBreakdown()
        var lines: [String] = []
        for i in 0..<300 {
            let content = UInt64(i) * 8 * ms + 1_000 * ms
            breakdown.recordSend(.init(contentNs: content, encodeStartNs: content + 2 * ms, encodeDoneNs: content + 8 * ms, sendNs: content + 9 * ms))
            let report = LatencyBreakdown.Report(contentNs: content, decodeUs: 7_000, waitUs: 1_000, presentUs: 4_000)
            if let line = breakdown.recordReport(report, receivedNs: content + 25 * ms) { lines.append(line) }
        }
        // 300 frames 8 ms apart: 2.4 s, so one 2 s window.
        XCTAssertEqual(lines.count, 1)
        XCTAssertTrue(lines[0].contains("total 25.0/25.0/25.0"), lines[0])
        XCTAssertFalse(lines[0].contains("unmatched"))
    }

    func testUnknownFramesCountAsUnmatched() {
        let breakdown = LatencyBreakdown()
        breakdown.recordSend(.init(contentNs: 0, encodeStartNs: 0, encodeDoneNs: 0, sendNs: 0))
        let r = LatencyBreakdown.Report(contentNs: 42, decodeUs: 1, waitUs: 1, presentUs: 1)
        XCTAssertNil(breakdown.recordReport(r, receivedNs: 10))
        let content: UInt64 = 5_000 * ms
        breakdown.recordSend(.init(contentNs: content, encodeStartNs: content, encodeDoneNs: content, sendNs: content))
        let line = breakdown.recordReport(.init(contentNs: content, decodeUs: 1, waitUs: 1, presentUs: 1), receivedNs: content + 1 * ms)
        XCTAssertNotNil(line)
        XCTAssertTrue(line?.contains("1 unmatched") == true, line ?? "")
    }
}
