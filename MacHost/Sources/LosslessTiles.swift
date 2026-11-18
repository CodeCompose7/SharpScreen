import Foundation
import Compression
import CoreGraphics
import CoreVideo
import os

// M1b: lossless tiles over the HEVC stream. See docs/design/M1-lossless-tiles.md.

/// The tile grid over the encoded frame (pixels, origin top-left, row-major).
struct TileGrid: Equatable {
    static let defaultTileSize = 64

    let width: Int
    let height: Int
    let tileSize: Int

    init(width: Int, height: Int, tileSize: Int = TileGrid.defaultTileSize) {
        self.width = max(0, width)
        self.height = max(0, height)
        self.tileSize = max(1, tileSize)
    }

    /// The grid for frames of `width` x `height`, or nil when this one already fits.
    func resized(width: Int, height: Int) -> TileGrid? {
        width == self.width && height == self.height ? nil : TileGrid(width: width, height: height, tileSize: tileSize)
    }

    var columns: Int { (width + tileSize - 1) / tileSize }
    var rows: Int { (height + tileSize - 1) / tileSize }
    var count: Int { columns * rows }

    /// Pixel rect of a tile, clipped at the right and bottom edges.
    func rect(of index: Int) -> (x: Int, y: Int, width: Int, height: Int) {
        let x = (index % columns) * tileSize
        let y = (index / columns) * tileSize
        return (x, y, min(tileSize, width - x), min(tileSize, height - y))
    }

    /// Tiles touched by a pixel rect (clipped to the frame).
    func tiles(covering r: CGRect) -> [Int] {
        let minX = max(0, Int(r.minX.rounded(.down)))
        let minY = max(0, Int(r.minY.rounded(.down)))
        let maxX = min(width, Int(r.maxX.rounded(.up)))
        let maxY = min(height, Int(r.maxY.rounded(.up)))
        guard maxX > minX, maxY > minY else { return [] }
        var out: [Int] = []
        for row in (minY / tileSize)...((maxY - 1) / tileSize) {
            for col in (minX / tileSize)...((maxX - 1) / tileSize) {
                out.append(row * columns + col)
            }
        }
        return out
    }
}

/// Per-tile state on the host. All mutations run on the server's frame queue
/// (the same serial queue that writes to the socket), so "invalidate", "send
/// tile" and "mark valid" happen in wire order. Reads for picking refine
/// candidates take the lock from another queue.
final class TileTracker {
    private struct State {
        var grid: TileGrid
        var lastChangeNs: [UInt64]
        var valid: [Bool]
    }

    private let lock: OSAllocatedUnfairLock<State>

    init(grid: TileGrid) {
        lock = OSAllocatedUnfairLock(initialState: State(grid: grid,
                                                          lastChangeNs: Array(repeating: 0, count: grid.count),
                                                          valid: Array(repeating: false, count: grid.count)))
    }

    var grid: TileGrid { lock.withLock { $0.grid } }

    /// Forget everything (new client, new size). Nothing is valid on the client.
    func reset(grid: TileGrid, nowNs: UInt64) {
        lock.withLock { s in
            s.grid = grid
            // Treat every tile as just changed so a fresh client gets a full
            // refine pass once the screen has been still for `settle`.
            s.lastChangeNs = Array(repeating: nowNs, count: grid.count)
            s.valid = Array(repeating: false, count: grid.count)
        }
    }

    /// Record changed pixels. Returns the tiles that were valid on the client
    /// and must be invalidated before the video frame that shows the change.
    func markDirty(_ rects: [CGRect], atNs: UInt64) -> [Int] {
        lock.withLock { s in
            var invalidated: [Int] = []
            for r in rects {
                for t in s.grid.tiles(covering: r) {
                    if atNs > s.lastChangeNs[t] { s.lastChangeNs[t] = atNs }
                    if s.valid[t] {
                        s.valid[t] = false
                        invalidated.append(t)
                    }
                }
            }
            return invalidated
        }
    }

    /// Tiles not valid on the client that have not changed for `settleNs`,
    /// most recently changed first (what the user just looked at).
    func refineCandidates(nowNs: UInt64, settleNs: UInt64, limit: Int) -> [Int] {
        lock.withLock { s in
            var c: [(Int, UInt64)] = []
            for t in 0..<s.grid.count where !s.valid[t] {
                let changed = s.lastChangeNs[t]
                if nowNs >= changed, nowNs - changed >= settleNs { c.append((t, changed)) }
            }
            c.sort { $0.1 > $1.1 }
            return c.prefix(limit).map(\.0)
        }
    }

    /// Of `tiles`, those still unchanged since a frame captured at `captureNs`
    /// and not yet valid; marks them valid. Call right before sending them.
    func claimForSend(_ tiles: [Int], capturedAtNs captureNs: UInt64) -> [Int] {
        lock.withLock { s in
            tiles.filter { t in
                guard t < s.grid.count, !s.valid[t], s.lastChangeNs[t] < captureNs else { return false }
                s.valid[t] = true
                return true
            }
        }
    }

    var validCount: Int { lock.withLock { $0.valid.filter { $0 }.count } }
}

/// One encoded tile: BGRA pixels, LZ4 block compressed (or raw if LZ4 did
/// not help).
struct EncodedTile: Equatable {
    let index: Int
    let rawLength: Int
    let payload: Data
    let compressed: Bool
}

enum LosslessTileCodec {
    /// Crop a tile out of a 32BGRA buffer and LZ4-compress it.
    static func encodeTile(_ index: Int, grid: TileGrid, buffer: CVPixelBuffer) -> EncodedTile? {
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA,
              CVPixelBufferGetWidth(buffer) == grid.width,
              CVPixelBufferGetHeight(buffer) == grid.height else { return nil }
        let r = grid.rect(of: index)
        let rowBytes = r.width * 4
        var raw = Data(count: rowBytes * r.height)
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        raw.withUnsafeMutableBytes { dst in
            for row in 0..<r.height {
                let src = base + (r.y + row) * stride + r.x * 4
                memcpy(dst.baseAddress! + row * rowBytes, src, rowBytes)
            }
        }
        return encodeRaw(index: index, raw: raw)
    }

    static func encodeRaw(index: Int, raw: Data) -> EncodedTile {
        let capacity = raw.count + raw.count / 255 + 64
        var out = Data(count: capacity)
        let n = out.withUnsafeMutableBytes { dst in
            raw.withUnsafeBytes { src in
                compression_encode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, capacity,
                                          src.bindMemory(to: UInt8.self).baseAddress!, raw.count,
                                          nil, COMPRESSION_LZ4_RAW)
            }
        }
        if n > 0, n < raw.count {
            return EncodedTile(index: index, rawLength: raw.count, payload: out.prefix(n), compressed: true)
        }
        return EncodedTile(index: index, rawLength: raw.count, payload: raw, compressed: false)
    }

    /// Wire type 20: u16 count, then count x u16 tile index (big-endian).
    static func invalidateMessage(_ tiles: [Int]) -> Data {
        var d = Data(capacity: 3 + tiles.count * 2)
        d.append(WireMessage.tileInvalidate)
        appendU16(&d, tiles.count)
        for t in tiles { appendU16(&d, t) }
        return d
    }

    /// Wire type 21: u16 tileSize, u16 count, then per tile
    /// u16 index, u32 rawLength, u32 payloadLength (0 = payload is raw), payload.
    static func tilesMessage(_ tiles: [EncodedTile], tileSize: Int) -> Data {
        var d = Data(capacity: 5 + tiles.reduce(0) { $0 + 10 + $1.payload.count })
        d.append(WireMessage.losslessTiles)
        appendU16(&d, tileSize)
        appendU16(&d, tiles.count)
        for t in tiles {
            appendU16(&d, t.index)
            appendU32(&d, t.rawLength)
            appendU32(&d, t.compressed ? t.payload.count : 0)
            d.append(t.payload)
        }
        return d
    }

    private static func appendU16(_ d: inout Data, _ v: Int) {
        d.append(UInt8((v >> 8) & 0xFF)); d.append(UInt8(v & 0xFF))
    }

    private static func appendU32(_ d: inout Data, _ v: Int) {
        d.append(UInt8((v >> 24) & 0xFF)); d.append(UInt8((v >> 16) & 0xFF))
        d.append(UInt8((v >> 8) & 0xFF)); d.append(UInt8(v & 0xFF))
    }
}
