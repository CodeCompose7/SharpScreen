package io.sharpscreen.app

import java.io.DataInputStream
import java.io.IOException

// M1b: lossless tiles over the HEVC stream. See docs/design/M1-lossless-tiles.md
// and the host side in MacHost/Sources/LosslessTiles.swift.

/** The tile grid over the stream frame (pixels, origin top-left, row-major). Mirrors the host's TileGrid. */
data class TileGrid(
    val width: Int,
    val height: Int,
    val tileSize: Int = 64,
) {
    val columns get() = (width + tileSize - 1) / tileSize
    val rows get() = (height + tileSize - 1) / tileSize
    val count get() = columns * rows

    fun x(index: Int) = (index % columns) * tileSize

    fun y(index: Int) = (index / columns) * tileSize

    fun tileWidth(index: Int) = minOf(tileSize, width - x(index))

    fun tileHeight(index: Int) = minOf(tileSize, height - y(index))
}

/** A decoded tile: BGRA bytes, rows packed (tileWidth * 4 per row). */
class DecodedTile(
    val index: Int,
    val pixels: ByteArray,
)

/** LZ4 block format decoder (what Apple's COMPRESSION_LZ4_RAW writes). */
object Lz4Block {
    /** Decompress exactly [dstLen] bytes; throws IOException on malformed input. */
    fun decompress(
        src: ByteArray,
        srcOff: Int,
        srcLen: Int,
        dst: ByteArray,
        dstOff: Int,
        dstLen: Int,
    ): Int {
        var sp = srcOff
        val sEnd = srcOff + srcLen
        var dp = dstOff
        val dEnd = dstOff + dstLen
        while (sp < sEnd) {
            val token = src[sp++].toInt() and 0xFF
            var literals = token ushr 4
            if (literals == 15) {
                var b: Int
                do {
                    if (sp >= sEnd) throw IOException("lz4: truncated literal length")
                    b = src[sp++].toInt() and 0xFF
                    literals += b
                } while (b == 255)
            }
            if (sp + literals > sEnd || dp + literals > dEnd) throw IOException("lz4: literal overrun")
            System.arraycopy(src, sp, dst, dp, literals)
            sp += literals
            dp += literals
            if (sp >= sEnd) break // the last sequence carries literals only
            if (sp + 2 > sEnd) throw IOException("lz4: truncated offset")
            val offset = (src[sp].toInt() and 0xFF) or ((src[sp + 1].toInt() and 0xFF) shl 8)
            sp += 2
            if (offset == 0 || offset > dp - dstOff) throw IOException("lz4: bad offset $offset")
            var match = (token and 0x0F) + 4
            if ((token and 0x0F) == 15) {
                var b: Int
                do {
                    if (sp >= sEnd) throw IOException("lz4: truncated match length")
                    b = src[sp++].toInt() and 0xFF
                    match += b
                } while (b == 255)
            }
            if (dp + match > dEnd) throw IOException("lz4: match overrun")
            var from = dp - offset
            if (offset >= match) {
                System.arraycopy(dst, from, dst, dp, match)
                dp += match
            } else {
                // Overlapping copy (run-length style): byte by byte, in order.
                repeat(match) { dst[dp++] = dst[from++] }
            }
        }
        if (dp != dEnd) throw IOException("lz4: decoded ${dp - dstOff} of $dstLen bytes")
        return dp - dstOff
    }
}

/** Wire types 20 and 21 (big-endian), mirroring LosslessTileCodec on the host. */
object LosslessTileWire {
    const val CLIENT_SUPPORTS_LOSSLESS_TILES = 19
    const val TILE_INVALIDATE = 20
    const val LOSSLESS_TILES = 21

    /** Payload of the client's type-19 opt-in: protocol 1, high bit set. */
    const val PROTOCOL_BYTE = 0x81

    fun readInvalidate(input: DataInputStream): IntArray {
        val count = input.readUnsignedShort()
        return IntArray(count) { input.readUnsignedShort() }
    }

    /** Reads a type-21 body and decodes every tile; returns (tileSize, tiles). */
    fun readTiles(input: DataInputStream): Pair<Int, List<DecodedTile>> {
        val tileSize = input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val out = ArrayList<DecodedTile>(count)
        var scratch = ByteArray(0)
        repeat(count) {
            val index = input.readUnsignedShort()
            val rawLen = input.readInt()
            val payloadLen = input.readInt()
            if (rawLen <= 0 || rawLen > MAX_TILE_BYTES || payloadLen < 0 || payloadLen > MAX_TILE_BYTES) {
                throw IOException("bad tile lengths raw=$rawLen payload=$payloadLen")
            }
            val pixels = ByteArray(rawLen)
            if (payloadLen == 0) {
                input.readFully(pixels)
            } else {
                if (scratch.size < payloadLen) scratch = ByteArray(payloadLen)
                input.readFully(scratch, 0, payloadLen)
                Lz4Block.decompress(scratch, 0, payloadLen, pixels, 0, rawLen)
            }
            out.add(DecodedTile(index, pixels))
        }
        return tileSize to out
    }

    private const val MAX_TILE_BYTES = 256 * 256 * 4
}
