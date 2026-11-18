import Foundation
import CoreMedia
import CoreVideo
import ScreenCaptureKit
import os

/// Sends lossless copies of tiles that stopped changing (M1b).
///
/// Every 8 ms it asks the server's TileTracker for tiles that have been still
/// for `settleNs` and are not yet valid on the client, LZ4-encodes them from a
/// 32BGRA capture taken after their last change and hands them to the server,
/// which re-checks each against later changes right before writing it. Work
/// per tick is capped by bytes and time so refining never crowds out video.
///
/// Frames come from a 32BGRA capture stream of their own (ScreenCapture's
/// tile stream) or, with diag.tileStream=false, the video stream in 32BGRA.
final class TileRefiner {
    static let settleNs: UInt64 = 100_000_000          // 100 ms without change
    static let tickNs: UInt64 = 8_333_333              // 1/120 s
    /// 1 MB/tick: a full 2560x1600 photo-wallpaper fill took 76-95 ms instead
    /// of 111-136 ms at 512 KB, video frame age unchanged (max < 20 ms). Past
    /// ~750 KB the 4 ms work cap binds, so 2 MB gained nothing. Peaks at ~40%
    /// of adb's measured ~300 MB/s only while a fill runs. diag.tileKBPerTick
    /// overrides.
    static let maxBytesPerTick = CaptureDiagnostics.tileBytesPerTick
    static let maxBytesPerTickWireless = CaptureDiagnostics.tileBytesPerTickWireless
    static let maxWorkNsPerTick: UInt64 = 4_000_000    // leave the queue idle half the tick

    private struct Latest {
        var buffer: CVPixelBuffer?
        var capturedAtNs: UInt64 = 0
    }

    private let latest = OSAllocatedUnfairLock(initialState: Latest())
    private let queue = DispatchQueue(label: "TileRefiner", qos: .userInitiated)
    private var timer: DispatchSourceTimer?
    private weak var server: StreamingServer?
    private var grid = TileGrid(width: 0, height: 0)
    private let active = OSAllocatedUnfairLock(initialState: false)

    var isActive: Bool { active.withLock { $0 } }

    func start(server: StreamingServer, grid: TileGrid) {
        stop()
        self.server = server
        self.grid = grid
        active.withLock { $0 = true }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .nanoseconds(Int(Self.tickNs)), repeating: .nanoseconds(Int(Self.tickNs)))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    func stop() {
        active.withLock { $0 = false }
        timer?.cancel()
        timer = nil
        latest.withLockUnchecked { $0 = Latest() }
    }

    /// Called for every captured 32BGRA frame with new pixels, in capture
    /// order. `capturedAtNs` must not be later than the moment the frame's
    /// pixels were taken (see ScreenCapture's tile stream).
    func noteFrame(_ buffer: CVPixelBuffer, capturedAtNs: UInt64) {
        latest.withLockUnchecked { $0 = Latest(buffer: buffer, capturedAtNs: capturedAtNs) }
    }

    private var lastDumpNs: UInt64 = 0

    private func tick() {
        guard let server, server.clientWantsLosslessTiles else { return }
        let frame = latest.withLockUnchecked { $0 }
        guard let buffer = frame.buffer else { return }
        let startNs = DispatchTime.now().uptimeNanoseconds
        if CaptureDiagnostics.dumpFrames, startNs - lastDumpNs > 2_000_000_000 {
            lastDumpNs = startNs
            CaptureDiagnostics.dump(buffer, to: "/tmp/sharpscreen_frame.bgra")
        }
        // The capture size changes when the stream does (new resolution, a new
        // client decode limit). Follow it: a grid of the old size made every tile
        // encode fail, which silently stopped lossless tiles until reconnect.
        if let resized = grid.resized(width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)) {
            debugLog("Lossless tiles: frame is now \(resized.width)x\(resized.height), new grid \(resized.columns)x\(resized.rows)")
            grid = resized
            server.resetLosslessTiles(grid: resized)
            return
        }
        let changes = server.tileTracker.refineCandidateChanges(nowNs: startNs, settleNs: Self.settleNs, limit: 512)
        // Only a frame taken after a tile's last change can serve it; the
        // server would drop the others anyway, after they cost an encode.
        let candidates = changes.filter { $0.changedNs < frame.capturedAtNs }.map(\.tile)
        guard !candidates.isEmpty else { return }

        var batch: [EncodedTile] = []
        var bytes = 0
        for index in candidates {
            guard let tile = LosslessTileCodec.encodeTile(index, grid: grid, buffer: buffer) else { return }
            batch.append(tile)
            bytes += tile.payload.count + 10
            if bytes >= (server.clientIsOverUSB ? Self.maxBytesPerTick : Self.maxBytesPerTickWireless) || DispatchTime.now().uptimeNanoseconds - startNs >= Self.maxWorkNsPerTick {
                break
            }
        }
        server.sendLosslessTiles(batch, capturedAtNs: frame.capturedAtNs, tileSize: grid.tileSize)
    }

    /// SCStreamFrameInfo.dirtyRects: pixel rects, origin top-left (measured on
    /// a 2960x1848 HiDPI capture, scaleFactor 2).
    static func dirtyRects(of sample: CMSampleBuffer) -> [CGRect] {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let info = attachments.first,
              let rects = info[.dirtyRects] as? [NSDictionary] else { return [] }
        return rects.compactMap { CGRect(dictionaryRepresentation: $0 as CFDictionary) }
    }
}
