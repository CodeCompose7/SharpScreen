import Darwin

/// Whether AWDL (Apple Wireless Direct Link: AirDrop, Handoff, Universal
/// Control, Sidecar) is up. While it is, the Mac's Wi-Fi leaves its channel
/// about twice a second: plain ping to the router measured p99 72 ms with
/// AWDL up and 8 ms with it down, and wireless streams stalled ~80-90 ms on
/// the same beat (docs/measurements.md, "Wi-Fi").
enum AWDLStatus {
    static let interfaceName = "awdl0"

    static func isActive() -> Bool {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return false }
        defer { freeifaddrs(list) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = cursor {
            if String(cString: ifa.pointee.ifa_name) == interfaceName {
                let flags = Int32(ifa.pointee.ifa_flags)
                if flags & IFF_UP != 0 && flags & IFF_RUNNING != 0 { return true }
            }
            cursor = ifa.pointee.ifa_next
        }
        return false
    }
}
