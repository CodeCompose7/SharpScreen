import Darwin
import Foundation
import IOKit
import IOUSBHost
import os

/// Loopback ports of the TCP connections AccessoryLink opens to the streaming
/// server. The server looks its clients up here to tell an accessory link
/// (USB without ADB) from adb reverse; both arrive from 127.0.0.1.
enum AccessoryClientPorts {
    private static let ports = OSAllocatedUnfairLock(initialState: Set<UInt16>())

    static func insert(_ port: UInt16) { ports.withLock { _ = $0.insert(port) } }
    static func remove(_ port: UInt16) { ports.withLock { _ = $0.remove(port) } }
    static func contains(_ port: UInt16) -> Bool { ports.withLock { $0.contains(port) } }
}

/// One tablet connected over Android Open Accessory (issue #3): no ADB on
/// the tablet or the Mac. Switches the device into accessory mode, then
/// relays the tablet's sessions (UsbLink frames) to the streaming server on
/// 127.0.0.1, exactly like adb reverse would. Stopping resets the device so
/// the tablet leaves accessory mode and the app sees it detach.
///
/// Measured on a Tab S11 Ultra (docs/measurements.md): SuperSpeed, 69 MB/s
/// to the tablet with USB debugging on, 104 MB/s with it off; 0.17 ms round
/// trip for a touch-sized message.
final class AccessoryLink {
    enum State: Equatable {
        case switching
        /// In accessory mode; the SharpScreen app on the tablet has not
        /// answered yet (first-run prompt, or the app is not installed).
        case waitingForApp
        case ready
        case failed(String)
    }

    /// The accessory strings: the tablet app's accessory filter matches the
    /// manufacturer and model; Android offers the URI when no app matches.
    static let manufacturer = "SharpScreen"
    static let model = "SharpScreen"
    static let downloadURI = "https://github.com/CodeCompose7/SharpScreen/releases/latest"

    static let googleVendorID = 0x18D1
    static let accessoryProductIDs: Set<Int> = [0x2D00, 0x2D01, 0x2D04, 0x2D05]

    let serial: String
    let locationID: Int
    private let serverPort: UInt16
    private let queue = DispatchQueue(label: "AccessoryLink.control", qos: .userInitiated)
    private let usbQueue = DispatchQueue(label: "AccessoryLink.usb", qos: .userInteractive)

    /// Called on the main queue.
    var onStateChange: ((State) -> Void)?

    private let lock = NSLock()
    private var running = true
    private var device: IOUSBHostDevice?
    private var interface: IOUSBHostInterface?
    private var inPipe: IOUSBHostPipe?
    private var outPipe: IOUSBHostPipe?
    private var maxPacket = 512
    private let writeLock = NSLock()
    /// Current session: the tablet's id and our socket to the server.
    private var session: (id: UInt8, fd: Int32, port: UInt16)?

    init(serial: String, locationID: Int, serverPort: UInt16) {
        self.serial = serial
        self.locationID = locationID
        self.serverPort = serverPort
    }

    func start() {
        queue.async { [weak self] in self?.run() }
    }

    /// Ends the link. `reset` puts the tablet back in its normal USB mode
    /// (always, except when the device is already gone).
    func stop(reset: Bool = true) {
        lock.lock()
        let wasRunning = running
        running = false
        let dev = device
        lock.unlock()
        guard wasRunning else { return }
        closeSession(notifyTablet: false)
        try? inPipe?.__abort(with: .asynchronous)
        try? outPipe?.__abort(with: .asynchronous)
        if reset, let dev {
            do { try dev.reset() } catch { debugLog("USB accessory: reset failed: \(error)") }
        }
        interface?.destroy()
        dev?.destroy()
        lock.lock()
        interface = nil
        device = nil
        inPipe = nil
        outPipe = nil
        lock.unlock()
        debugLog("USB accessory: link to \(serial) stopped (reset=\(reset))")
    }

    private var isRunning: Bool {
        lock.lock(); defer { lock.unlock() }
        return running
    }

    private func report(_ state: State) {
        DispatchQueue.main.async { [weak self] in self?.onStateChange?(state) }
    }

    // MARK: - Switching into accessory mode

    private func run() {
        report(.switching)
        do {
            // A previous run (or a crash) can leave the tablet in accessory
            // mode, mid-stream. Reset it first so both ends start clean.
            if let stale = Self.service(locationID: locationID, accessory: true) {
                debugLog("USB accessory: \(serial) still in accessory mode — resetting it first")
                let dev = try IOUSBHostDevice(__ioService: stale, options: [], queue: usbQueue, interestHandler: nil)
                try? dev.reset()
                dev.destroy()
                IOObjectRelease(stale)
                guard waitFor(accessory: false, timeout: 8) else { throw LinkError("tablet did not return from accessory mode") }
            }
            guard let normal = Self.service(locationID: locationID, accessory: false) else { throw LinkError("device gone") }
            defer { IOObjectRelease(normal) }
            let dev = try IOUSBHostDevice(__ioService: normal, options: [], queue: usbQueue, interestHandler: nil)
            defer { dev.destroy() }
            let version = try Self.protocolVersion(dev)
            guard version >= 1 else { throw LinkError("device does not support Android Open Accessory") }
            let strings = [Self.manufacturer, Self.model, "SharpScreen display", Self.appVersion, Self.downloadURI, ""]
            for (index, string) in strings.enumerated() {
                try Self.control(dev, type: 0x40, request: 52, index: UInt16(index), data: NSMutableData(data: Data((string + "\0").utf8)))
            }
            try Self.control(dev, type: 0x40, request: 53, index: 0, data: nil)
            debugLog("USB accessory: \(serial) asked to switch (AOA v\(version))")
        } catch {
            fail("switch: \(error)")
            return
        }
        guard isRunning else { return }
        guard waitFor(accessory: true, timeout: 10) else {
            fail("the tablet did not enter accessory mode")
            return
        }
        guard isRunning else { return }
        do {
            try openAccessory()
        } catch {
            fail("open: \(error)")
            return
        }
        report(.waitingForApp)
        usbQueue.async { [weak self] in self?.readLoop() }
    }

    private func openAccessory() throws {
        // The interface nub can lag the device by a moment after enumeration.
        var found: (io_service_t, io_service_t)?
        for _ in 0..<30 {
            if let dev = Self.service(locationID: locationID, accessory: true) {
                if let intf = Self.interfaceService(locationID: locationID, number: 0) {
                    found = (dev, intf)
                    break
                }
                IOObjectRelease(dev)
            }
            usleep(100_000)
        }
        guard let (devService, intfService) = found else { throw LinkError("accessory interface missing") }
        defer {
            IOObjectRelease(devService)
            IOObjectRelease(intfService)
        }
        let dev = try IOUSBHostDevice(__ioService: devService, options: [], queue: usbQueue, interestHandler: nil)
        let intf = try IOUSBHostInterface(__ioService: intfService, options: [], queue: usbQueue, interestHandler: nil)
        var bulkIn: IOUSBHostPipe?
        var bulkOut: IOUSBHostPipe?
        // Accessory endpoints are one bulk IN and one bulk OUT; addresses vary.
        for address in 1...15 {
            if bulkOut == nil, let pipe = try? intf.copyPipe(withAddress: address) { bulkOut = pipe }
            if bulkIn == nil, let pipe = try? intf.copyPipe(withAddress: address | 0x80) { bulkIn = pipe }
            if bulkIn != nil, bulkOut != nil { break }
        }
        guard let bulkIn, let bulkOut else {
            intf.destroy()
            dev.destroy()
            throw LinkError("accessory endpoints missing")
        }
        let packet = Int(bulkOut.descriptors.pointee.descriptor.wMaxPacketSize & 0x7FF)
        lock.lock()
        device = dev
        interface = intf
        inPipe = bulkIn
        outPipe = bulkOut
        maxPacket = max(packet, 64)
        lock.unlock()
        debugLog("USB accessory: \(serial) open, max packet \(packet)")
    }

    private func waitFor(accessory: Bool, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline, isRunning {
            if let s = Self.service(locationID: locationID, accessory: accessory) {
                IOObjectRelease(s)
                return true
            }
            usleep(100_000)
        }
        return false
    }

    private func fail(_ message: String) {
        guard isRunning else { return }
        debugLog("USB accessory: \(serial) failed — \(message)")
        report(.failed(message))
        stop(reset: false)
    }

    // MARK: - Relay

    private func readLoop() {
        var parser = UsbLink.Parser()
        let buffer = NSMutableData(length: 64 * 1024)!
        while isRunning {
            guard let pipe = inPipe else { break }
            var count = 0
            do {
                try pipe.__sendIORequest(with: buffer, bytesTransferred: &count, completionTimeout: 0)
            } catch {
                if isRunning {
                    // Unplugged, or reset under us: nothing to reset any more.
                    debugLog("USB accessory: \(serial) read ended — \(error)")
                    report(.failed("disconnected"))
                    stop(reset: false)
                }
                return
            }
            guard count > 0 else { continue }
            for frame in parser.feed(Data(bytes: buffer.bytes, count: count)) {
                handle(frame)
            }
        }
    }

    private func handle(_ frame: UsbLink.Frame) {
        switch frame.type {
        case .hello:
            debugLog("USB accessory: \(serial) app is up (link v\(frame.payload.first ?? 0))")
            try? send(UsbLink.encode(.hello, session: 0, payload: Data([UsbLink.version])))
            report(.ready)
        case .open:
            openSession(frame.session)
        case .data:
            lock.lock()
            let current = session
            lock.unlock()
            guard let current, current.id == frame.session else { return }
            if !Self.writeAll(current.fd, frame.payload) {
                closeSession(notifyTablet: true)
            }
        case .close:
            lock.lock()
            let matches = session?.id == frame.session
            lock.unlock()
            if matches { closeSession(notifyTablet: false) }
        }
    }

    private func openSession(_ id: UInt8) {
        closeSession(notifyTablet: false)
        guard let (fd, port) = Self.connectLoopback(port: serverPort) else {
            debugLog("USB accessory: server not reachable on \(serverPort)")
            try? send(UsbLink.encode(.close, session: id))
            return
        }
        lock.lock()
        session = (id, fd, port)
        lock.unlock()
        let thread = Thread { [weak self] in self?.serverReadLoop(id: id, fd: fd) }
        thread.name = "AccessoryLink.server"
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    /// Server -> tablet.
    private func serverReadLoop(id: UInt8, fd: Int32) {
        let size = 60 * 1024
        let buffer = UnsafeMutableRawPointer.allocate(byteCount: size, alignment: 8)
        defer { buffer.deallocate() }
        while true {
            let n = recv(fd, buffer, size, 0)
            if n <= 0 { break }
            do {
                try send(UsbLink.encode(.data, session: id, payload: Data(bytes: buffer, count: n)))
            } catch {
                if isRunning {
                    debugLog("USB accessory: \(serial) write failed — \(error)")
                    report(.failed("write failed"))
                    stop(reset: true)
                }
                return
            }
        }
        lock.lock()
        let stillCurrent = session?.id == id && session?.fd == fd
        lock.unlock()
        if stillCurrent { closeSession(notifyTablet: true) }
    }

    private func closeSession(notifyTablet: Bool) {
        lock.lock()
        let old = session
        session = nil
        lock.unlock()
        guard let old else { return }
        shutdown(old.fd, SHUT_RDWR)
        close(old.fd)
        AccessoryClientPorts.remove(old.port)
        if notifyTablet { try? send(UsbLink.encode(.close, session: old.id)) }
    }

    private func send(_ frame: Data) throws {
        writeLock.lock()
        defer { writeLock.unlock() }
        guard let pipe = outPipe else { throw LinkError("not open") }
        var offset = 0
        for size in UsbLink.transferSizes(length: frame.count, maxPacket: maxPacket) {
            let chunk = NSMutableData(data: frame.subdata(in: offset..<(offset + size)))
            var sent = 0
            // A tablet that stops reading for this long has gone away.
            try pipe.__sendIORequest(with: chunk, bytesTransferred: &sent, completionTimeout: 3)
            offset += size
        }
    }

    // MARK: - Helpers

    struct LinkError: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }

    static func intProperty(_ service: io_service_t, _ key: String) -> Int? {
        (IORegistryEntryCreateCFProperty(service, key as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? NSNumber)?.intValue
    }

    static func isAccessory(vendor: Int?, product: Int?) -> Bool {
        vendor == googleVendorID && product.map(accessoryProductIDs.contains) == true
    }

    /// The device at `locationID`, in accessory mode or not. Caller releases.
    static func service(locationID: Int, accessory: Bool) -> io_service_t? {
        firstService("IOUSBHostDevice") { s in
            intProperty(s, "locationID") == locationID
                && isAccessory(vendor: intProperty(s, "idVendor"), product: intProperty(s, "idProduct")) == accessory
        }
    }

    static func interfaceService(locationID: Int, number: Int) -> io_service_t? {
        firstService("IOUSBHostInterface") { s in
            intProperty(s, "locationID") == locationID && intProperty(s, "bInterfaceNumber") == number
        }
    }

    private static func firstService(_ className: String, where match: (io_service_t) -> Bool) -> io_service_t? {
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching(className), &iterator) == KERN_SUCCESS else { return nil }
        defer { IOObjectRelease(iterator) }
        var service = IOIteratorNext(iterator)
        while service != 0 {
            if match(service) { return service }
            IOObjectRelease(service)
            service = IOIteratorNext(iterator)
        }
        return nil
    }

    /// ACCESSORY_GET_PROTOCOL (51): 0 = no accessory support.
    static func protocolVersion(_ dev: IOUSBHostDevice) throws -> Int {
        let data = NSMutableData(length: 2)!
        try control(dev, type: 0xC0, request: 51, index: 0, data: data)
        let bytes = data.bytes.assumingMemoryBound(to: UInt8.self)
        return Int(bytes[0]) | Int(bytes[1]) << 8
    }

    static func control(_ dev: IOUSBHostDevice, type: UInt8, request: UInt8, index: UInt16, data: NSMutableData?) throws {
        var req = IOUSBDeviceRequest()
        req.bmRequestType = type
        req.bRequest = request
        req.wValue = 0
        req.wIndex = index
        req.wLength = UInt16(data?.length ?? 0)
        var transferred = 0
        try dev.__send(req, data: data, bytesTransferred: &transferred, completionTimeout: 1.0)
    }

    /// A TCP connection to the server from a known local port.
    private static func connectLoopback(port: UInt16) -> (Int32, UInt16)? {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        var one: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var local = sockaddr_in()
        local.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        local.sin_family = sa_family_t(AF_INET)
        local.sin_addr.s_addr = inet_addr("127.0.0.1")
        local.sin_port = 0
        var bound = withUnsafePointer(to: &local) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        if bound == 0 {
            bound = withUnsafeMutablePointer(to: &local) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
            }
        }
        guard bound == 0 else {
            close(fd)
            return nil
        }
        let localPort = UInt16(bigEndian: local.sin_port)
        // Registered before connecting: the server checks it when it accepts.
        AccessoryClientPorts.insert(localPort)
        var server = sockaddr_in()
        server.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        server.sin_family = sa_family_t(AF_INET)
        server.sin_addr.s_addr = inet_addr("127.0.0.1")
        server.sin_port = port.bigEndian
        let connected = withUnsafePointer(to: &server) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard connected == 0 else {
            AccessoryClientPorts.remove(localPort)
            close(fd)
            return nil
        }
        return (fd, localPort)
    }

    private static func writeAll(_ fd: Int32, _ data: Data) -> Bool {
        data.withUnsafeBytes { raw in
            var offset = 0
            while offset < raw.count {
                let n = Darwin.send(fd, raw.baseAddress! + offset, raw.count - offset, 0)
                if n <= 0 { return false }
                offset += n
            }
            return true
        }
    }
}
