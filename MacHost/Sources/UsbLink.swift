import Foundation

/// The framing both apps put on the USB accessory link (issue #3).
///
/// Android Open Accessory gives one raw byte pipe each way and no notion of a
/// connection: the tablet can't tell the Mac "I disconnected", and bytes from
/// a finished stream could leak into the next one. Each side therefore relays
/// its local TCP stream (the Mac's streaming server, the tablet's
/// StreamClient) inside small frames that carry a session number:
///
///     [type u8][session u8][payload length u16, big endian][payload]
///
/// The tablet opens a session when StreamClient connects to its relay; either
/// side closes it. DATA for any other session is dropped. Mirrored in
/// AndroidClient's UsbLink.kt.
enum UsbLink {
    static let version: UInt8 = 1
    static let headerSize = 4
    static let maxPayload = 0xFFFF

    enum FrameType: UInt8 {
        /// Link is up (both directions, session 0). Payload: [version].
        case hello = 1
        /// Tablet -> Mac: start session `session` (connect to the server).
        case open = 2
        /// Stream bytes for `session`.
        case data = 3
        /// Either side: session `session` ended.
        case close = 4
    }

    struct Frame: Equatable {
        var type: FrameType
        var session: UInt8
        var payload: Data
    }

    static func encode(_ type: FrameType, session: UInt8, payload: Data = Data()) -> Data {
        precondition(payload.count <= maxPayload)
        var out = Data(capacity: headerSize + payload.count)
        out.append(type.rawValue)
        out.append(session)
        out.append(UInt8(payload.count >> 8))
        out.append(UInt8(payload.count & 0xFF))
        out.append(payload)
        return out
    }

    /// Splits one outgoing USB transfer so none ends on a whole packet. The
    /// tablet's accessory driver reads in 16 KB requests and completes one only
    /// when it fills or a short packet arrives; a transfer that is an exact
    /// multiple of the packet size would sit there until the next frame.
    /// Sending the last byte on its own always ends on a short packet, and
    /// avoids zero-length packets (which reach the app as empty reads).
    static func transferSizes(length: Int, maxPacket: Int) -> [Int] {
        guard length > 0, maxPacket > 1, length % maxPacket == 0 else { return length > 0 ? [length] : [] }
        return [length - 1, 1]
    }

    /// Incremental parser for the incoming byte stream.
    struct Parser {
        private var buffer = Data()

        /// Feeds bytes and returns every complete frame, in order. Unknown
        /// frame types are skipped (a newer peer may send more kinds).
        mutating func feed(_ bytes: Data) -> [Frame] {
            buffer.append(bytes)
            var frames: [Frame] = []
            var offset = buffer.startIndex
            while buffer.endIndex - offset >= UsbLink.headerSize {
                let typeByte = buffer[offset]
                let session = buffer[offset + 1]
                let length = Int(buffer[offset + 2]) << 8 | Int(buffer[offset + 3])
                let end = offset + UsbLink.headerSize + length
                guard end <= buffer.endIndex else { break }
                if let type = FrameType(rawValue: typeByte) {
                    frames.append(Frame(type: type, session: session, payload: buffer.subdata(in: (offset + UsbLink.headerSize)..<end)))
                }
                offset = end
            }
            buffer.removeSubrange(buffer.startIndex..<offset)
            return frames
        }
    }
}
