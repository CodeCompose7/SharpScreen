package io.sharpscreen.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * LZ4 vectors were compressed on macOS with Compression.framework
 * (COMPRESSION_LZ4_RAW), the encoder the host uses, from the same generators.
 */
class LosslessTilesTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun text(n: Int) =
        ByteArray(n) { i ->
            val x = i / 4
            (if ((x * 7 + (x / 64) * 3) % 11 == 0) 20 else 235).toByte()
        }

    private fun noise(n: Int): ByteArray {
        var s = 12345L
        return ByteArray(n) {
            s = (s * 1103515245L + 12345L) and 0xFFFFFFFFL
            ((s ushr 16) and 0xFF).toByte()
        }
    }

    private fun decode(compressed: ByteArray, rawLen: Int): ByteArray {
        val out = ByteArray(rawLen)
        Lz4Block.decompress(compressed, 0, compressed.size, out, 0, rawLen)
        return out
    }

    @Test
    fun decodesAppleLz4Text() = assertArrayEquals(text(4096), decode(hex(TEXT), 4096))

    @Test
    fun decodesAppleLz4Incompressible() = assertArrayEquals(noise(300), decode(hex(NOISE), 300))

    @Test
    fun decodesAppleLz4LongRun() = assertArrayEquals(ByteArray(16384), decode(hex(ZERO), 16384))

    @Test
    fun rejectsTruncatedInput() {
        val c = hex(TEXT)
        assertThrows(IOException::class.java) { Lz4Block.decompress(c, 0, c.size - 5, ByteArray(4096), 0, 4096) }
    }

    @Test
    fun gridMatchesTheHost() {
        val g = TileGrid(2960, 1848)
        assertEquals(47, g.columns)
        assertEquals(29, g.rows)
        assertEquals(16, g.tileWidth(46))
        assertEquals(56, g.tileHeight(28 * 47))
        assertEquals(2944, g.x(1362))
    }

    @Test
    fun readsTheHostWireLayout() {
        // Bytes LosslessTileCodec.tilesMessage produces (see LosslessTilesTests.swift),
        // after the type byte: one raw tile, index 0, 2 bytes.
        val body = byteArrayOf(0, 64, 0, 1, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 7, 8)
        val (size, tiles) = LosslessTileWire.readTiles(DataInputStream(ByteArrayInputStream(body)))
        assertEquals(64, size)
        assertEquals(0, tiles.single().index)
        assertArrayEquals(byteArrayOf(7, 8), tiles.single().pixels)
        val inval = LosslessTileWire.readInvalidate(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 2, 0, 1, 1, 44))))
        assertArrayEquals(intArrayOf(1, 300), inval)
    }

    companion object {
        private const val TEXT =
            "5f14141414eb0100140f2c00c10fd400c104ce010fdc00110f240011034f000f2c00c20fd400c103af010fb001120f24" +
            "0011034f000f500012032b000f2c00960fa800950357010f58013e0f50003d03a7000fa8003e0357000f58003e0f5000" +
            "3d03a7000fa8003e0357000f58003e0f50003d03a7000fa8003e0357000f58003e0f50003d03a7000fa8003e0357000f" +
            "58003e0f50003d03a7000fa8003e0357000f58003e0f50003d03a7000fa8003e0357000f58003e0f50003d03a7000fa8" +
            "003e0357000f58003e0f50003d03a7000fa8003e0357000f58003e0f50003d03a7000fa8003e0357000f58003e0f5000" +
            "3d03a7000fa80019f06eeb14141414ebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebeb" +
            "ebebebebebebeb14141414ebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebeb" +
            "ebebeb14141414ebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebebeb"
        private const val NOISE =
            "f0ff1edc0465aa1fad1d5adae5ac1b1e5f1370796cfd10ff19af601d04acb41d022b4678733af2df5faeb70859d1ee39" +
            "10cb4895b5cc892911ff06b6622edf3cf935fd4b9428ca097c44b3025e965fb3ea6dacd42d816e69afe0e6874c9c04e7" +
            "d2365d2c60c9eaf479f686a0eb9326e46212d50dcbb377156a6a3a68ba8edb7408469ef3ceb30af8d0dd68bbf85ffa24" +
            "f2d2fc1887fb5c87bab43832a59b1b3d107cf778d67fe26df81191297e9395cb12c557ce5af1d41618d719bc045b7e99" +
            "65f1a29471c42aac6aa938c475c7ad3238021f053b2c991afceb15decf68bae07cbcd61e971b9a0b9dbe9763d392fcaf" +
            "dfa28c97234562ebdd076570ff58896acff7caee3f1ce9e40a68e5de938d389c7dbdd75b09d4e7e233443f4a8cc4a190" +
            "d6b8b8dc615fd18e28be590eaa501b"
        private const val ZERO =
            "1f000100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" +
            "ffffffffffffffffffffffffffffffffffffffb0f06c0000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00"
    }
}
