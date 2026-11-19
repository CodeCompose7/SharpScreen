package io.sharpscreen.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** USB accessory link framing (issue #3); mirrors MacHost UsbLinkTests. */
class UsbLinkTest {
    @Test
    fun frameRoundTrip() {
        val payload = ByteArray(300) { it.toByte() }
        val bytes = UsbLink.encode(UsbLink.DATA, 7, payload)
        assertEquals(UsbLink.HEADER_SIZE + 300, bytes.size)
        assertArrayEquals(byteArrayOf(3, 7, 0x01, 0x2C), bytes.copyOfRange(0, 4))
        val frames = UsbLink.Parser().feed(bytes, bytes.size)
        assertEquals(1, frames.size)
        assertEquals(UsbLink.DATA, frames[0].type)
        assertEquals(7, frames[0].session)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun encodeTakesASliceOfTheBuffer() {
        val buffer = byteArrayOf(0, 1, 2, 3, 4, 5)
        val bytes = UsbLink.encode(UsbLink.DATA, 200, buffer, 2, 3)
        assertArrayEquals(byteArrayOf(3, 200.toByte(), 0, 3, 2, 3, 4), bytes)
        assertEquals(200, UsbLink.Parser().feed(bytes, bytes.size)[0].session)
    }

    @Test
    fun parserHandlesSplitAndBatchedInput() {
        val stream =
            UsbLink.encode(UsbLink.OPEN, 1) +
                UsbLink.encode(UsbLink.DATA, 1, byteArrayOf(9, 8, 7)) +
                UsbLink.encode(UsbLink.CLOSE, 1)
        val parser = UsbLink.Parser()
        val frames = stream.flatMap { parser.feed(byteArrayOf(it), 1) }
        assertEquals(listOf(UsbLink.OPEN, UsbLink.DATA, UsbLink.CLOSE), frames.map { it.type })
        assertArrayEquals(byteArrayOf(9, 8, 7), frames[1].payload)
        assertEquals(3, UsbLink.Parser().feed(stream, stream.size).size)
    }

    @Test
    fun parserGrowsForLargeFramesAndSkipsUnknownTypes() {
        val big = UsbLink.encode(UsbLink.DATA, 2, ByteArray(UsbLink.MAX_PAYLOAD) { 5 })
        val input = byteArrayOf(42, 0, 0, 1, 1) + big
        val frames = UsbLink.Parser().feed(input, input.size)
        assertEquals(1, frames.size)
        assertEquals(UsbLink.MAX_PAYLOAD, frames[0].payload.size)
    }

    @Test
    fun writesNeverEndOnAWholePacket() {
        assertArrayEquals(intArrayOf(511, 1), UsbLink.writeSizes(512))
        assertArrayEquals(intArrayOf(1023, 1), UsbLink.writeSizes(1024))
        assertArrayEquals(intArrayOf(100), UsbLink.writeSizes(100))
        assertArrayEquals(IntArray(0), UsbLink.writeSizes(0))
        for (length in listOf(1, 4, 511, 512, 1024, 16384, 65539)) {
            val sizes = UsbLink.writeSizes(length)
            assertEquals(length, sizes.sum())
            assertTrue(sizes.all { it % 512 != 0 })
        }
    }
}
