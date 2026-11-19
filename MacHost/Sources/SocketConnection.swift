import Darwin
import Foundation
import Network

/// The streaming server's TCP, on plain BSD sockets (issue #9).
///
/// Connections Network.framework's NWListener accepted went out over Wi-Fi in
/// large bursts (a tablet's kernel saw ~3,000 segments for 30 MB, ~21,000 from
/// a plain socket), and on many connections 15-27% of them reached the tablet
/// out of order. Each gap holds back everything behind it, and the Mac's TCP
/// took the duplicate ACKs for loss and slowed down: 50-100 ms freezes while
/// dragging windows over Wi-Fi. Plain sockets, accepted the same way: 0%.
///
/// These two classes keep the NWListener / NWConnection calls the server
/// already makes (states, send with completion, receive with minimum and
/// maximum, endpoint, cancel), so StreamingServer only changes types.

/// A listening socket on all interfaces, IPv4 and IPv6.
final class SocketListener {
    var newConnectionHandler: ((SocketConnection) -> Void)?
    let port: UInt16
    private let fd: Int32
    private var source: DispatchSourceRead?

    init(port requested: UInt16) throws {
        let fd = socket(AF_INET6, SOCK_STREAM, 0)
        guard fd >= 0 else { throw Self.posixError() }
        var off: Int32 = 0
        var on: Int32 = 1
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &off, socklen_t(MemoryLayout<Int32>.size))
        // Restart on the same port while old connections sit in TIME_WAIT.
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in6()
        address.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        address.sin6_family = sa_family_t(AF_INET6)
        address.sin6_port = requested.bigEndian
        address.sin6_addr = in6addr_any
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) }
        }
        guard bound == 0, listen(fd, 8) == 0 else {
            let error = Self.posixError()
            close(fd)
            throw error
        }
        var actual = sockaddr_in6()
        var length = socklen_t(MemoryLayout<sockaddr_in6>.size)
        _ = withUnsafeMutablePointer(to: &actual) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        self.fd = fd
        self.port = UInt16(bigEndian: actual.sin6_port)
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
    }

    func start(queue: DispatchQueue) {
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in self?.acceptPending() }
        let fd = self.fd
        source.setCancelHandler { close(fd) }
        self.source = source
        source.resume()
    }

    func cancel() {
        if let source {
            source.cancel()
            self.source = nil
        } else {
            close(fd)
        }
    }

    private func acceptPending() {
        while true {
            var storage = sockaddr_storage()
            var length = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let client = withUnsafeMutablePointer(to: &storage) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { accept(fd, $0, &length) }
            }
            if client < 0 { return } // EAGAIN: none left
            SocketConnection.configure(client)
            newConnectionHandler?(SocketConnection(fd: client, endpoint: Self.endpoint(from: storage)))
        }
    }

    /// The peer as an NWEndpoint; IPv4-mapped addresses come back as IPv4,
    /// so 127.0.0.1 still reads as loopback.
    static func endpoint(from storage: sockaddr_storage) -> NWEndpoint {
        var storage = storage
        if Int32(storage.ss_family) == AF_INET {
            return withUnsafePointer(to: &storage) {
                $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin in
                    let raw = withUnsafeBytes(of: sin.pointee.sin_addr) { Data($0) }
                    return .hostPort(host: .ipv4(IPv4Address(raw)!), port: NWEndpoint.Port(rawValue: UInt16(bigEndian: sin.pointee.sin_port))!)
                }
            }
        }
        return withUnsafePointer(to: &storage) {
            $0.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { sin6 in
                let raw = withUnsafeBytes(of: sin6.pointee.sin6_addr) { Data($0) }
                let port = NWEndpoint.Port(rawValue: UInt16(bigEndian: sin6.pointee.sin6_port))!
                if raw.prefix(10).allSatisfy({ $0 == 0 }), raw[10] == 0xFF, raw[11] == 0xFF {
                    return .hostPort(host: .ipv4(IPv4Address(raw.suffix(4))!), port: port)
                }
                return .hostPort(host: .ipv6(IPv6Address(raw)!), port: port)
            }
        }
    }

    static func posixError() -> NWError {
        .posix(POSIXErrorCode(rawValue: errno) ?? .EIO)
    }
}

/// One accepted TCP connection with NWConnection's calls. Thread-safe: all
/// work runs on a private queue; callbacks go to the queue given to start().
final class SocketConnection {
    let endpoint: NWEndpoint
    var stateUpdateHandler: ((NWConnection.State) -> Void)?

    typealias ReceiveCompletion = (Data?, NWConnection.ContentContext?, Bool, NWError?) -> Void

    private let fd: Int32
    private let io = DispatchQueue(label: "SocketConnection", qos: .userInteractive)
    private var callbackQueue = DispatchQueue.main
    private var readSource: DispatchSourceRead?
    private var writeSource: DispatchSourceWrite?
    private var writeSourceRunning = false
    private var writes: [(data: Data, offset: Int, done: ((NWError?) -> Void)?)] = []
    private var readBuffer = Data()
    private var pendingReceive: (min: Int, max: Int, completion: ReceiveCompletion)?
    private var reachedEnd = false
    private var finished = false
    private var readCancelled = false
    /// The fd closes once both sources have finished cancelling.
    private let sourcesDone = DispatchGroup()

    init(fd: Int32, endpoint: NWEndpoint) {
        self.fd = fd
        self.endpoint = endpoint
    }

    /// TCP_NODELAY, no SIGPIPE, non-blocking, and the interactive video
    /// service class (WMM AC_VI on Wi-Fi).
    static func configure(_ fd: Int32) {
        var on: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var serviceType: Int32 = 3 // NET_SERVICE_TYPE_VI
        setsockopt(fd, SOL_SOCKET, 0x1116 /* SO_NET_SERVICE_TYPE */, &serviceType, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
    }

    func start(queue: DispatchQueue) {
        io.async { [self] in
            callbackQueue = queue
            guard !finished else { return }
            let read = DispatchSource.makeReadSource(fileDescriptor: fd, queue: io)
            read.setEventHandler { [weak self] in self?.readAvailable() }
            let write = DispatchSource.makeWriteSource(fileDescriptor: fd, queue: io)
            write.setEventHandler { [weak self] in self?.flushWrites() }
            let group = sourcesDone
            group.enter()
            group.enter()
            read.setCancelHandler { group.leave() }
            write.setCancelHandler { group.leave() }
            let fd = self.fd
            group.notify(queue: io) { close(fd) }
            readSource = read
            writeSource = write
            read.resume()
            queue.async { [weak self] in self?.stateUpdateHandler?(.ready) }
        }
    }

    func send(content: Data?, completion: NWConnection.SendCompletion) {
        var done: ((NWError?) -> Void)?
        if case .contentProcessed(let handler) = completion { done = handler }
        io.async { [self] in
            guard !finished else {
                if let done { callbackQueue.async { done(.posix(.ECANCELED)) } }
                return
            }
            writes.append((content ?? Data(), 0, done))
            flushWrites()
        }
    }

    func receive(minimumIncompleteLength: Int, maximumLength: Int, completion: @escaping ReceiveCompletion) {
        io.async { [self] in
            guard !finished || !readBuffer.isEmpty else {
                callbackQueue.async { completion(nil, nil, true, nil) }
                return
            }
            pendingReceive = (max(minimumIncompleteLength, 1), max(maximumLength, 1), completion)
            deliverReceive()
        }
    }

    func cancel() {
        io.async { [self] in finish(state: .cancelled, error: .posix(.ECANCELED)) }
    }

    /// Bytes waiting here or in the kernel's send buffer (not yet
    /// acknowledged): how far the link is behind.
    var queuedBytes: Int {
        var kernel: Int32 = 0
        var length = socklen_t(MemoryLayout<Int32>.size)
        getsockopt(fd, SOL_SOCKET, 0x1024 /* SO_NWRITE */, &kernel, &length)
        let mine = io.sync { writes.reduce(0) { $0 + $1.data.count - $1.offset } }
        return Int(kernel) + mine
    }

    // MARK: - Private (io queue)

    private func readAvailable() {
        var chunk = [UInt8](repeating: 0, count: 64 * 1024)
        while true {
            let n = chunk.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress, $0.count) }
            if n > 0 {
                readBuffer.append(contentsOf: chunk[0..<n])
                continue
            }
            if n == 0 {
                reachedEnd = true
            } else if errno != EAGAIN && errno != EWOULDBLOCK {
                deliverReceive()
                finish(state: .failed(SocketListener.posixError()), error: SocketListener.posixError())
                return
            }
            break
        }
        if reachedEnd, !readCancelled {
            // Readable forever at EOF: stop watching.
            readCancelled = true
            readSource?.cancel()
        }
        deliverReceive()
    }

    private func deliverReceive() {
        guard let request = pendingReceive else { return }
        if readBuffer.count >= request.min || (reachedEnd && !readBuffer.isEmpty) {
            let count = min(request.max, readBuffer.count)
            let data = readBuffer.prefix(count)
            readBuffer.removeFirst(count)
            pendingReceive = nil
            callbackQueue.async { request.completion(Data(data), nil, false, nil) }
        } else if reachedEnd {
            pendingReceive = nil
            callbackQueue.async { request.completion(nil, nil, true, nil) }
        }
        // The peer closed and everything was read: report it the way
        // NWConnection reports a reset, so the server sees the client go.
        if reachedEnd, readBuffer.isEmpty {
            finish(state: .failed(.posix(.ECONNRESET)), error: .posix(.ECONNRESET))
        }
    }

    private func flushWrites() {
        while !finished, !writes.isEmpty {
            let item = writes[0]
            let remaining = item.data.count - item.offset
            if remaining > 0 {
                let n = item.data.withUnsafeBytes { raw in
                    Darwin.write(fd, raw.baseAddress! + item.offset, remaining)
                }
                if n < 0 {
                    if errno == EAGAIN || errno == EWOULDBLOCK {
                        startWriteSource()
                        return
                    }
                    let error = SocketListener.posixError()
                    finish(state: .failed(error), error: error)
                    return
                }
                writes[0].offset += n
                if writes[0].offset < item.data.count { continue }
            }
            writes.removeFirst()
            if let done = item.done { callbackQueue.async { done(nil) } }
        }
        stopWriteSource()
    }

    private func startWriteSource() {
        guard !writeSourceRunning, let writeSource else { return }
        writeSourceRunning = true
        writeSource.resume()
    }

    private func stopWriteSource() {
        guard writeSourceRunning, let writeSource else { return }
        writeSourceRunning = false
        writeSource.suspend()
    }

    private func finish(state: NWConnection.State, error: NWError) {
        guard !finished else { return }
        finished = true
        if let request = pendingReceive {
            pendingReceive = nil
            callbackQueue.async { request.completion(nil, nil, true, nil) }
        }
        for item in writes {
            if let done = item.done { callbackQueue.async { done(error) } }
        }
        writes.removeAll()
        shutdown(fd, SHUT_RDWR)
        if let writeSource, let readSource {
            // A suspended source must be resumed before it can be cancelled;
            // the fd closes when both cancel handlers have run.
            if !writeSourceRunning { writeSource.resume() }
            writeSource.cancel()
            if !readCancelled { readSource.cancel() }
        } else {
            close(fd) // never started
        }
        readCancelled = true
        readSource = nil
        writeSource = nil
        let handler = stateUpdateHandler
        callbackQueue.async { handler?(state) }
    }
}
