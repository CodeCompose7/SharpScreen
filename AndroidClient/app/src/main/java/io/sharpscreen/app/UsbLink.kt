package io.sharpscreen.app

/**
 * Framing on the USB accessory link (issue #3); mirrors MacHost's UsbLink.swift.
 *
 *     [type u8][session u8][payload length u16, big endian][payload]
 *
 * The tablet opens a session when StreamClient connects to the local relay;
 * either side closes it. DATA for any other session is dropped, so bytes of a
 * finished stream never leak into the next.
 */
object UsbLink {
    const val VERSION = 1
    const val HEADER_SIZE = 4
    const val MAX_PAYLOAD = 0xFFFF

    const val HELLO = 1
    const val OPEN = 2
    const val DATA = 3
    const val CLOSE = 4

    class Frame(
        val type: Int,
        val session: Int,
        val payload: ByteArray,
    )

    fun encode(
        type: Int,
        session: Int,
        payload: ByteArray = ByteArray(0),
        offset: Int = 0,
        length: Int = payload.size,
    ): ByteArray {
        require(length in 0..MAX_PAYLOAD)
        val out = ByteArray(HEADER_SIZE + length)
        out[0] = type.toByte()
        out[1] = session.toByte()
        out[2] = (length shr 8).toByte()
        out[3] = (length and 0xFF).toByte()
        System.arraycopy(payload, offset, out, HEADER_SIZE, length)
        return out
    }

    /**
     * Splits one write so none ends on a whole USB packet: the Mac reads in
     * large requests that complete only on a short packet, and a write of an
     * exact multiple would sit there until the next one. 512 covers both
     * high speed (512) and SuperSpeed (1024) packets.
     */
    fun writeSizes(
        length: Int,
        maxPacket: Int = 512,
    ): IntArray =
        when {
            length <= 0 -> IntArray(0)
            length % maxPacket == 0 -> intArrayOf(length - 1, 1)
            else -> intArrayOf(length)
        }

    /** Incremental parser for the byte stream from the Mac. */
    class Parser {
        private var buffer = ByteArray(64 * 1024)
        private var size = 0

        fun feed(
            bytes: ByteArray,
            count: Int,
        ): List<Frame> {
            if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
            System.arraycopy(bytes, 0, buffer, size, count)
            size += count
            val frames = ArrayList<Frame>(4)
            var offset = 0
            while (size - offset >= HEADER_SIZE) {
                val length = (buffer[offset + 2].toInt() and 0xFF shl 8) or (buffer[offset + 3].toInt() and 0xFF)
                val end = offset + HEADER_SIZE + length
                if (end > size) break
                val type = buffer[offset].toInt() and 0xFF
                if (type in HELLO..CLOSE) {
                    frames.add(Frame(type, buffer[offset + 1].toInt() and 0xFF, buffer.copyOfRange(offset + HEADER_SIZE, end)))
                }
                offset = end
            }
            if (offset > 0) {
                System.arraycopy(buffer, offset, buffer, 0, size - offset)
                size -= offset
            }
            return frames
        }
    }
}
