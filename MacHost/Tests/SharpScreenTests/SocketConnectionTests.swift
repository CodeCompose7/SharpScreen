import Darwin
import Network
import XCTest
@testable import SharpScreen

/// The streaming server's BSD-socket listener and connection (issue #9).
final class SocketConnectionTests: XCTestCase {
    private func connectClient(port: UInt16) -> Int32 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = port.bigEndian
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        XCTAssertEqual(result, 0)
        return fd
    }

    func testAcceptReceiveSendAndClose() throws {
        let listener = try SocketListener(port: 0)
        XCTAssertGreaterThan(listener.port, 0)
        let queue = DispatchQueue(label: "SocketConnectionTests")
        let accepted = expectation(description: "accepted")
        var server: SocketConnection?
        listener.newConnectionHandler = { connection in
            server = connection
            accepted.fulfill()
        }
        listener.start(queue: queue)
        defer { listener.cancel() }

        let client = connectClient(port: listener.port)
        defer { close(client) }
        wait(for: [accepted], timeout: 2)
        let connection = try XCTUnwrap(server)
        // 127.0.0.1 arrives on the dual-stack socket as IPv4-mapped IPv6.
        guard case .hostPort(let host, _) = connection.endpoint, case .ipv4(let v4) = host else {
            return XCTFail("expected an IPv4 endpoint, got \(connection.endpoint)")
        }
        XCTAssertTrue(v4.isLoopback)

        let states = expectation(description: "ready")
        var failedAfterClose = false
        let closed = expectation(description: "peer close reported")
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: states.fulfill()
            case .failed: if !failedAfterClose { failedAfterClose = true; closed.fulfill() }
            default: break
            }
        }
        connection.start(queue: queue)
        wait(for: [states], timeout: 2)

        // Client -> server, read with a minimum length.
        _ = "hello world".withCString { write(client, $0, 11) }
        let received = expectation(description: "received")
        connection.receive(minimumIncompleteLength: 11, maximumLength: 64) { data, _, complete, error in
            XCTAssertEqual(data.map { String(decoding: $0, as: UTF8.self) }, "hello world")
            XCTAssertFalse(complete)
            XCTAssertNil(error)
            received.fulfill()
        }
        wait(for: [received], timeout: 2)

        // Server -> client, a payload larger than one socket buffer write.
        let payload = Data((0..<300_000).map { UInt8($0 & 0xFF) })
        let sent = expectation(description: "sent")
        connection.send(content: payload, completion: .contentProcessed { error in
            XCTAssertNil(error)
            sent.fulfill()
        })
        var got = Data()
        var buffer = [UInt8](repeating: 0, count: 65536)
        while got.count < payload.count {
            let n = read(client, &buffer, buffer.count)
            XCTAssertGreaterThan(n, 0)
            if n <= 0 { break }
            got.append(contentsOf: buffer[0..<n])
        }
        wait(for: [sent], timeout: 5)
        XCTAssertEqual(got, payload)

        // Peer closes: reported as a failed (reset) state.
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64) { _, _, _, _ in }
        close(client)
        wait(for: [closed], timeout: 2)
    }

    func testPortInUseFailsToBind() throws {
        let first = try SocketListener(port: 0)
        defer { first.cancel() }
        XCTAssertThrowsError(try SocketListener(port: first.port))
    }
}
