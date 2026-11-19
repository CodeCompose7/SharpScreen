import Foundation
import IOKit

/// An Android device on a USB port, as IOKit sees it.
struct UsbAndroidDevice: Equatable, Identifiable {
    /// The USB serial number; the same string `adb devices` lists.
    let serial: String
    let locationID: Int
    let usbName: String
    /// In accessory mode right now (a link of ours, or a stale one).
    let isAccessory: Bool

    var id: String { serial }
}

enum UsbDeviceScanner {
    /// Android phones and tablets on USB. A device counts when it shows an
    /// Android interface (adb, MTP/PTP), calls itself Android, or is already
    /// in accessory mode. Storage and input devices never do, whatever their
    /// vendor (Samsung also makes SSDs).
    static func scan() -> [UsbAndroidDevice] {
        var devices: [UsbAndroidDevice] = []
        var interfaces: [Int: [(cls: Int, sub: Int, proto: Int)]] = [:]
        forEach("IOUSBHostInterface") { s in
            guard let location = AccessoryLink.intProperty(s, "locationID") else { return }
            interfaces[location, default: []].append((
                AccessoryLink.intProperty(s, "bInterfaceClass") ?? -1,
                AccessoryLink.intProperty(s, "bInterfaceSubClass") ?? -1,
                AccessoryLink.intProperty(s, "bInterfaceProtocol") ?? -1
            ))
        }
        forEach("IOUSBHostDevice") { s in
            guard let location = AccessoryLink.intProperty(s, "locationID"),
                  let serial = stringProperty(s, "USB Serial Number")?.trimmingCharacters(in: .whitespaces),
                  !serial.isEmpty else { return }
            let vendor = AccessoryLink.intProperty(s, "idVendor")
            let product = AccessoryLink.intProperty(s, "idProduct")
            let name = stringProperty(s, "USB Product Name") ?? ""
            let maker = stringProperty(s, "USB Vendor Name") ?? ""
            let accessory = AccessoryLink.isAccessory(vendor: vendor, product: product)
            let kinds = interfaces[location] ?? []
            if kinds.contains(where: { $0.cls == 0x08 || $0.cls == 0x03 }) { return } // storage, HID
            let androidInterface = kinds.contains { k in
                (k.cls == 0xFF && k.sub == 0x42 && k.proto == 0x01) // adb
                    || (k.cls == 0x06 && k.sub == 0x01) // MTP / PTP
            }
            let saysAndroid = (name + " " + maker).localizedCaseInsensitiveContains("android")
            guard accessory || androidInterface || saysAndroid else { return }
            devices.append(UsbAndroidDevice(serial: serial, locationID: location, usbName: name, isAccessory: accessory))
        }
        return devices.sorted { $0.locationID < $1.locationID }
    }

    private static func stringProperty(_ service: io_service_t, _ key: String) -> String? {
        IORegistryEntryCreateCFProperty(service, key as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? String
    }

    private static func forEach(_ className: String, _ body: (io_service_t) -> Void) {
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching(className), &iterator) == KERN_SUCCESS else { return }
        defer { IOObjectRelease(iterator) }
        var service = IOIteratorNext(iterator)
        while service != 0 {
            body(service)
            IOObjectRelease(service)
            service = IOIteratorNext(iterator)
        }
    }
}

/// What the user decided per tablet (USB serial), and which one to use.
final class UsbDeviceStore {
    enum Decision: String {
        /// Switch it into accessory mode (USB without ADB).
        case accessory
        /// Never switch it; it can still connect through adb.
        case adbOnly
    }

    private let defaults: UserDefaults
    private let decisionsKey = "SharpScreen_usbDeviceDecisions"
    private let namesKey = "SharpScreen_usbDeviceNames"
    private let preferredKey = "SharpScreen_usbPreferredDevice"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func decision(for serial: String) -> Decision? {
        (defaults.dictionary(forKey: decisionsKey)?[serial] as? String).flatMap(Decision.init(rawValue:))
    }

    func setDecision(_ decision: Decision?, for serial: String) {
        var all = defaults.dictionary(forKey: decisionsKey) ?? [:]
        all[serial] = decision?.rawValue
        defaults.set(all, forKey: decisionsKey)
    }

    var decidedSerials: [String] {
        (defaults.dictionary(forKey: decisionsKey) ?? [:]).keys.sorted()
    }

    /// Friendly name (the tablet app's own name for itself, or adb's model).
    func name(for serial: String) -> String? {
        defaults.dictionary(forKey: namesKey)?[serial] as? String
    }

    func setName(_ name: String, for serial: String) {
        var all = defaults.dictionary(forKey: namesKey) ?? [:]
        guard all[serial] as? String != name else { return }
        all[serial] = name
        defaults.set(all, forKey: namesKey)
    }

    func forget(_ serial: String) {
        setDecision(nil, for: serial)
        var names = defaults.dictionary(forKey: namesKey) ?? [:]
        names[serial] = nil
        defaults.set(names, forKey: namesKey)
        if preferred == serial { preferred = nil }
    }

    var preferred: String? {
        get { defaults.string(forKey: preferredKey) }
        set { defaults.set(newValue, forKey: preferredKey) }
    }
}

enum UsbDeviceSelection {
    /// The one tablet USB serves. Sticky: the current one stays while it is
    /// plugged in, so plugging in a second tablet never cuts a running
    /// stream. Otherwise the user's last pick, otherwise the first attached.
    static func active(connected: [String], current: String?, preferred: String?) -> String? {
        if let current, connected.contains(current) { return current }
        if let preferred, connected.contains(preferred) { return preferred }
        return connected.first
    }
}
