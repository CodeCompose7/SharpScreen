import AppKit
import Foundation

/// A tablet on USB, for the menu and settings.
struct UsbDeviceEntry: Identifiable, Equatable {
    enum Transport: Equatable {
        /// USB accessory mode: no ADB needed.
        case accessory
        case adb
        /// Neither yet (waiting for the user's answer, or for adb).
        case none
    }

    let serial: String
    let name: String
    let isActive: Bool
    let transport: Transport
    /// The accessory link's state, when it has one.
    let linkState: AccessoryLink.State?

    var id: String { serial }
}

/// Picks the one tablet USB serves and connects it (issue #3): over an
/// AccessoryLink when the user approved that device (no ADB anywhere),
/// otherwise through adb reverse. Only the active tablet gets either, so a
/// second tablet on the Mac can no longer take over (or keep cutting) the
/// first one's stream. Main thread only; driven by the 2 s status refresh.
final class UsbConnectionManager {
    let store = UsbDeviceStore()
    private let settings: DisplaySettings

    private(set) var activeSerial: String?
    private var link: AccessoryLink?
    private var linkState: AccessoryLink.State?
    /// Accessory links that failed stay off until the tablet is unplugged
    /// (or the user picks it again), instead of retrying every 2 s.
    private var failedLocations: Set<Int> = []
    /// "Not now" answers, until the app quits.
    private var postponed: Set<String> = []
    private var asking = false
    private var lastDevices: [UsbAndroidDevice] = []
    private var lastAdbSerials: [String] = []

    init(settings: DisplaySettings) {
        self.settings = settings
    }

    /// The adb device to reverse the port on, if the active tablet uses adb.
    var adbTarget: String? {
        guard let activeSerial, link == nil, lastAdbSerials.contains(activeSerial) else { return nil }
        return activeSerial
    }

    func refresh(serving: Bool, port: UInt16, adbSerials: [String]) {
        lastAdbSerials = adbSerials
        guard serving else {
            stopLink(reset: true)
            activeSerial = nil
            publish([])
            return
        }
        let usb = UsbDeviceScanner.scan()
        lastDevices = usb
        let usbLocations = Set(usb.map(\.locationID))
        failedLocations.formIntersection(usbLocations)

        // While linked, the tablet may enumerate under another serial in
        // accessory mode; its port (location) is what stays put.
        var connected = usb.map(\.serial)
        if let link, usbLocations.contains(link.locationID), !connected.contains(link.serial) {
            connected.append(link.serial)
        }
        for serial in adbSerials where !connected.contains(serial) {
            connected.append(serial)
        }

        let active = UsbDeviceSelection.active(connected: connected, current: activeSerial, preferred: store.preferred)
        if let link, link.serial != active || !usbLocations.contains(link.locationID) {
            // Unplugged: nothing to reset. Deselected: send it back to normal.
            stopLink(reset: usbLocations.contains(link.locationID))
        }
        activeSerial = active

        if let active, settings.usbAccessoryEnabled,
           let device = usb.first(where: { $0.serial == active || $0.locationID == link?.locationID }) {
            switch store.decision(for: active) {
            case .accessory:
                if link == nil, !failedLocations.contains(device.locationID) {
                    startLink(serial: active, device: device, port: port)
                }
            case .adbOnly:
                break
            case nil:
                if !postponed.contains(active) { ask(about: device, serial: active) }
            }
        } else if !settings.usbAccessoryEnabled {
            stopLink(reset: true)
        }
        publish(connected)
    }

    /// The user picked a tablet in the menu or settings.
    func select(_ serial: String) {
        store.preferred = serial
        postponed.remove(serial)
        if let device = lastDevices.first(where: { $0.serial == serial }) {
            failedLocations.remove(device.locationID)
        }
        // Picking the tablet in use again retries a failed accessory link.
        guard serial != activeSerial else {
            settings.onUsbDevicesChanged?()
            return
        }
        stopLink(reset: true)
        activeSerial = serial
        settings.onUsbDevicesChanged?()
    }

    func forget(_ serial: String) {
        if serial == activeSerial { stopLink(reset: true) }
        store.forget(serial)
        postponed.remove(serial)
        settings.onUsbDevicesChanged?()
    }

    /// The tablet named itself (stream identity): show that name from now on.
    func noteClientName(_ name: String) {
        guard let activeSerial, !name.isEmpty else { return }
        store.setName(name, for: activeSerial)
    }

    func stop() {
        stopLink(reset: true)
        activeSerial = nil
        publish([])
    }

    // MARK: - Private

    private func startLink(serial: String, device: UsbAndroidDevice, port: UInt16) {
        let newLink = AccessoryLink(serial: serial, locationID: device.locationID, serverPort: port)
        newLink.onStateChange = { [weak self, weak newLink] state in
            guard let self, let newLink, self.link === newLink else { return }
            self.linkState = state
            if case .failed = state {
                self.failedLocations.insert(newLink.locationID)
                self.link = nil
            }
            self.publish(self.settings.usbDevices.map(\.serial))
        }
        link = newLink
        linkState = .switching
        debugLog("USB: connecting \(serial) as a USB accessory")
        newLink.start()
    }

    private func stopLink(reset: Bool) {
        guard let link else { return }
        self.link = nil
        linkState = nil
        link.stop(reset: reset)
    }

    private func ask(about device: UsbAndroidDevice, serial: String) {
        guard !asking else { return }
        asking = true
        let name = store.name(for: serial) ?? device.usbName
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let alert = NSAlert()
            alert.messageText = String(localized: "Connect “\(name)” over USB without ADB?")
            alert.informativeText = String(localized: "While streaming, SharpScreen switches this device into USB accessory mode, so developer options and ADB are not needed. It returns to normal when streaming stops or the cable is unplugged.")
            alert.addButton(withTitle: String(localized: "Connect"))
            alert.addButton(withTitle: String(localized: "Use ADB Only"))
            alert.addButton(withTitle: String(localized: "Not Now"))
            NSApp.activate(ignoringOtherApps: true)
            let answer = alert.runModal()
            self.asking = false
            switch answer {
            case .alertFirstButtonReturn: self.store.setDecision(.accessory, for: serial)
            case .alertSecondButtonReturn: self.store.setDecision(.adbOnly, for: serial)
            default: self.postponed.insert(serial)
            }
            self.settings.onUsbDevicesChanged?()
        }
    }

    private func publish(_ connected: [String]) {
        let entries = connected.map { serial -> UsbDeviceEntry in
            let isActive = serial == activeSerial
            let usesLink = isActive && link?.serial == serial
            let linkFailed = isActive && !usesLink && store.decision(for: serial) == .accessory
                && lastDevices.first { $0.serial == serial }.map { failedLocations.contains($0.locationID) } == true
            let transport: UsbDeviceEntry.Transport =
                usesLink || linkFailed ? .accessory : (lastAdbSerials.contains(serial) ? .adb : .none)
            let usbName = lastDevices.first { $0.serial == serial }?.usbName
            return UsbDeviceEntry(
                serial: serial,
                name: store.name(for: serial) ?? usbName ?? serial,
                isActive: isActive,
                transport: transport,
                linkState: usesLink ? linkState : (linkFailed ? .failed("") : nil)
            )
        }
        if settings.usbDevices != entries { settings.usbDevices = entries }
    }
}
