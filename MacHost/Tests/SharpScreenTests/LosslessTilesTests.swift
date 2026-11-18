import XCTest
import Compression
import CoreVideo
@testable import SharpScreen

final class LosslessTilesTests: XCTestCase {
    // The Tab S11 Ultra stream: 2960x1848 -> 47 x 29 tiles, last column 16 px wide, last row 56 px tall.
    private let grid = TileGrid(width: 2960, height: 1848)

    func testGridSizeAndEdgeTiles() {
        XCTAssertEqual(grid.columns, 47)
        XCTAssertEqual(grid.rows, 29)
        XCTAssertEqual(grid.count, 1363)
        XCTAssertEqual(grid.rect(of: 0).width, 64)
        XCTAssertEqual(grid.rect(of: 46).width, 16)                 // right edge
        XCTAssertEqual(grid.rect(of: 28 * 47).height, 56)           // bottom edge
        XCTAssertEqual(grid.rect(of: 1362).x, 2944)
    }

    func testGridFollowsAFrameSizeChange() {
        XCTAssertNil(grid.resized(width: 2960, height: 1848), "same size keeps the grid")
        let smaller = grid.resized(width: 1920, height: 1200)
        XCTAssertEqual(smaller?.columns, 30)
        XCTAssertEqual(smaller?.rows, 19)
        XCTAssertEqual(smaller?.tileSize, 64)
    }

    func testDirtyRectMapsToTouchedTiles() {
        // A dirty rect ScreenCaptureKit reported for the moving test bar.
        let tiles = grid.tiles(covering: CGRect(x: 1464, y: 684, width: 112, height: 480))
        let cols = Set(tiles.map { $0 % 47 })
        let rows = Set(tiles.map { $0 / 47 })
        XCTAssertEqual(cols, Set(22...24))   // 1464/64 = 22.9 .. 1575/64 = 24.6
        XCTAssertEqual(rows, Set(10...18))   // 684/64 = 10.7 .. 1163/64 = 18.2
        XCTAssertEqual(grid.tiles(covering: CGRect(x: 5000, y: 0, width: 10, height: 10)), [])
        XCTAssertEqual(grid.tiles(covering: CGRect(x: 63, y: 63, width: 2, height: 2)), [0, 1, 47, 48])
    }

    func testTrackerOnlyInvalidatesTilesTheClientHas() {
        let t = TileTracker(grid: grid)
        t.reset(grid: grid, nowNs: 1_000)
        XCTAssertEqual(t.markDirty([CGRect(x: 0, y: 0, width: 10, height: 10)], atNs: 2_000), [], "nothing valid yet")
        XCTAssertEqual(t.claimForSend([0, 1], capturedAtNs: 3_000), [0, 1])
        XCTAssertEqual(t.markDirty([CGRect(x: 0, y: 0, width: 10, height: 10)], atNs: 4_000), [0])
        XCTAssertEqual(t.validCount, 1)
    }

    func testRefineWaitsForSettleAndPrefersRecentChanges() {
        let t = TileTracker(grid: TileGrid(width: 256, height: 64))   // 4 tiles
        t.reset(grid: TileGrid(width: 256, height: 64), nowNs: 0)
        _ = t.markDirty([CGRect(x: 0, y: 0, width: 1, height: 1)], atNs: 100)    // tile 0
        _ = t.markDirty([CGRect(x: 200, y: 0, width: 1, height: 1)], atNs: 300)  // tile 3
        XCTAssertEqual(t.refineCandidates(nowNs: 350, settleNs: 100, limit: 10), [0, 1, 2], "3 is still settling")
        XCTAssertEqual(t.refineCandidates(nowNs: 1_000, settleNs: 100, limit: 10), [3, 0, 1, 2])
        XCTAssertEqual(t.refineCandidates(nowNs: 1_000, settleNs: 100, limit: 2), [3, 0])
    }

    /// A resync on the same grid keeps what is known about changes, so a
    /// still screen refills from the frame already captured; a new grid
    /// starts with every tile changed.
    func testResetKeepsChangeHistoryOnlyOnTheSameGrid() {
        let small = TileGrid(width: 128, height: 64)   // 2 tiles
        let t = TileTracker(grid: small)
        t.reset(grid: small, nowNs: 0)
        _ = t.markDirty([CGRect(x: 0, y: 0, width: 1, height: 1)], atNs: 1_000)   // tile 0
        XCTAssertEqual(t.claimForSend([0, 1], capturedAtNs: 2_000), [0, 1])
        t.reset(grid: small, nowNs: 50_000)
        XCTAssertEqual(t.validCount, 0)
        XCTAssertEqual(t.refineCandidates(nowNs: 50_000, settleNs: 100, limit: 10), [0, 1], "still tiles need no new settle")
        XCTAssertEqual(t.claimForSend([0, 1], capturedAtNs: 2_000), [0, 1], "the old frame is still current")

        let wider = TileGrid(width: 192, height: 64)   // 3 tiles
        t.reset(grid: wider, nowNs: 60_000)
        XCTAssertEqual(t.refineCandidates(nowNs: 60_050, settleNs: 100, limit: 10), [], "a new size settles first")
        XCTAssertEqual(t.claimForSend([0, 1, 2], capturedAtNs: 2_000), [], "frames of the old size are out")
        XCTAssertEqual(t.claimForSend([0, 1, 2], capturedAtNs: 60_001), [0, 1, 2])
    }

    /// A tile captured before its latest change must not be sent: the client
    /// would paint old pixels over newer video.
    func testClaimRejectsTilesChangedAfterTheCapture() {
        let t = TileTracker(grid: grid)
        t.reset(grid: grid, nowNs: 0)
        _ = t.markDirty([CGRect(x: 0, y: 0, width: 1, height: 1)], atNs: 5_000)
        XCTAssertEqual(t.claimForSend([0, 1], capturedAtNs: 4_000), [1])
        XCTAssertEqual(t.claimForSend([0, 1], capturedAtNs: 6_000), [0], "1 is already valid")
    }

    func testTileEncodingRoundTripsThroughLZ4() throws {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(nil, 128, 64, kCVPixelFormatType_32BGRA, nil, &buffer)
        let pb = try XCTUnwrap(buffer)
        CVPixelBufferLockBaseAddress(pb, [])
        let base = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(pb)
        for y in 0..<64 { for x in 0..<(128 * 4) { base[y * stride + x] = UInt8((x / 4 + y * 3) % 7 == 0 ? 20 : 235) } }
        CVPixelBufferUnlockBaseAddress(pb, [])

        let g = TileGrid(width: 128, height: 64)
        let tile = try XCTUnwrap(LosslessTileCodec.encodeTile(1, grid: g, buffer: pb))
        XCTAssertTrue(tile.compressed)
        XCTAssertEqual(tile.rawLength, 64 * 64 * 4)
        XCTAssertLessThan(tile.payload.count, tile.rawLength / 4)

        var decoded = [UInt8](repeating: 0, count: tile.rawLength)
        let n = tile.payload.withUnsafeBytes { src in
            compression_decode_buffer(&decoded, decoded.count, src.bindMemory(to: UInt8.self).baseAddress!,
                                      tile.payload.count, nil, COMPRESSION_LZ4_RAW)
        }
        XCTAssertEqual(n, tile.rawLength)
        // Row 5, pixel 3 of tile 1 is source pixel (64 + 3, 5).
        let expected: UInt8 = (67 + 15) % 7 == 0 ? 20 : 235
        XCTAssertEqual(decoded[5 * 64 * 4 + 3 * 4], expected)
    }

    func testWireLayout() {
        let inval = LosslessTileCodec.invalidateMessage([1, 300])
        XCTAssertEqual([UInt8](inval), [20, 0, 2, 0, 1, 1, 44])
        let tile = EncodedTile(index: 258, rawLength: 16384, payload: Data([9, 9, 9]), compressed: true)
        let msg = LosslessTileCodec.tilesMessage([tile], tileSize: 64)
        XCTAssertEqual([UInt8](msg), [21, 0, 64, 0, 1, 1, 2, 0, 0, 64, 0, 0, 0, 0, 3, 9, 9, 9])
        let raw = EncodedTile(index: 0, rawLength: 2, payload: Data([7, 8]), compressed: false)
        XCTAssertEqual([UInt8](LosslessTileCodec.tilesMessage([raw], tileSize: 64)).suffix(12),
                       [0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 7, 8])
    }
}
