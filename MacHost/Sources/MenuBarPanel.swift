import AppKit
import Combine
import SwiftUI

/// The menu bar popup: a small panel under the status item, like the
/// system's Control Center modules (Bluetooth, Wi-Fi) and apps such as
/// Tailscale. An NSMenu opened by the status item sits lower than those, and
/// one opened by hand loses the status item's selected look; a panel keeps
/// both right.
final class MenuBarPanelController {
    struct Actions {
        var toggleServer: () -> Void
        var showSettings: () -> Void
        var quit: () -> Void
    }

    /// Space between the menu bar's bottom edge and the panel.
    private static let gap: CGFloat = 5
    private static let width: CGFloat = 300

    private let panel: MenuBarPanel
    private let hosting: NSHostingView<MenuBarPanelView>
    private weak var button: NSStatusBarButton?
    private var monitors: [Any] = []
    private let actions: Actions
    private var stateChange: AnyCancellable?

    var isShown: Bool { panel.isVisible }
    /// True when the status item's expanded-interface session (macOS 27+)
    /// drives opening, closing and the selected look.
    var systemManagesSelection = false
    /// Ends that session; set by the owner when it uses one.
    var onDismiss: (() -> Void)?

    init(settings: DisplaySettings, actions: Actions) {
        self.actions = actions
        panel = MenuBarPanel(
            contentRect: NSRect(x: 0, y: 0, width: Self.width, height: 200),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: true
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .popUpMenu
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false

        let effect = NSVisualEffectView()
        effect.material = .popover
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 14
        effect.layer?.cornerCurve = .continuous
        effect.layer?.masksToBounds = true

        var close: () -> Void = {}
        hosting = NSHostingView(rootView: MenuBarPanelView(
            settings: settings,
            actions: .init(
                toggleServer: actions.toggleServer,
                showSettings: { close(); actions.showSettings() },
                quit: actions.quit
            )
        ))
        hosting.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(hosting)
        NSLayoutConstraint.activate([
            hosting.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
            hosting.trailingAnchor.constraint(equalTo: effect.trailingAnchor),
            hosting.topAnchor.constraint(equalTo: effect.topAnchor),
            hosting.bottomAnchor.constraint(equalTo: effect.bottomAnchor),
        ])
        panel.contentView = effect
        close = { [weak self] in self?.dismiss() }

        // Content height follows the state (wireless device name, waiting...).
        stateChange = settings.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                // After SwiftUI has applied the change.
                DispatchQueue.main.async {
                    guard let self, self.panel.isVisible else { return }
                    self.fitToContent()
                }
            }
    }

    func toggle(from button: NSStatusBarButton) {
        isShown ? close() : show(from: button)
    }

    func show(from button: NSStatusBarButton) {
        self.button = button
        fitToContent()
        panel.orderFrontRegardless()
        panel.makeKey()
        // macOS 27+ shows the item as selected itself during an expanded
        // interface session; before that the button is highlighted by hand.
        if !systemManagesSelection {
            DispatchQueue.main.async { button.highlight(true) }
        }
        installMonitors()
    }

    func close() {
        guard panel.isVisible else { return }
        panel.orderOut(nil)
        if !systemManagesSelection { button?.highlight(false) }
        removeMonitors()
    }

    /// Closed by the user inside the app (outside click, Escape, an action):
    /// with a system session the system must be told, and it then calls back
    /// to close the panel.
    private func dismiss() {
        if let endSession = onDismiss {
            endSession()
        } else {
            close()
        }
    }

    /// Size to the SwiftUI content and pin the top under the status item.
    private func fitToContent() {
        guard let button, let barWindow = button.window else { return }
        let height = ceil(hosting.fittingSize.height)
        let item = barWindow.convertToScreen(button.convert(button.bounds, to: nil))
        let screen = barWindow.screen ?? NSScreen.main
        var x = item.minX - 8
        if let visible = screen?.visibleFrame {
            x = min(max(x, visible.minX + 8), visible.maxX - Self.width - 8)
        }
        let top = barWindow.frame.minY - Self.gap
        let frame = NSRect(x: x, y: top - height, width: Self.width, height: height)
        if panel.frame != frame { panel.setFrame(frame, display: true) }
    }

    private func installMonitors() {
        removeMonitors()
        // A click anywhere else (other apps, the desktop, the menu bar).
        if let global = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] _ in
            self?.dismiss()
        }) {
            monitors.append(global)
        }
        // A click in another of this app's windows (the settings window).
        if let localClick = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] event in
            guard let self else { return event }
            // Clicks on the status item itself are the system session's to handle.
            if event.window !== self.panel, event.window !== self.button?.window { self.dismiss() }
            return event
        }) {
            monitors.append(localClick)
        }
        if let local = NSEvent.addLocalMonitorForEvents(matching: [.keyDown], handler: { [weak self] event in
            guard let self, self.panel.isKeyWindow else { return event }
            if event.keyCode == 53 { // Escape
                self.dismiss()
                return nil
            }
            guard event.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command else { return event }
            switch event.charactersIgnoringModifiers {
            case "t": self.actions.toggleServer(); return nil
            case "s": self.dismiss(); self.actions.showSettings(); return nil
            case "q": self.actions.quit(); return nil
            default: return event
            }
        }) {
            monitors.append(local)
        }
    }

    private func removeMonitors() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors.removeAll()
    }
}

/// Borderless panels refuse key status by default; this one takes it so
/// Escape and the shortcuts reach it, without activating the app.
final class MenuBarPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

struct MenuBarPanelView: View {
    @ObservedObject var settings: DisplaySettings
    let actions: MenuBarPanelController.Actions

    private var statusColor: Color {
        guard settings.isRunning else { return .gray }
        return settings.clientConnected ? .green : .orange
    }

    private var statusText: String {
        guard settings.isRunning else { return String(localized: "Server stopped") }
        guard settings.clientConnected else {
            if settings.connectionMode == .usb,
               let active = settings.usbDevices.first(where: \.isActive), active.transport == .accessory {
                return UsbDeviceText.status(active)
            }
            return String(localized: "Waiting for tablet on port \(String(settings.port))")
        }
        if let device = settings.currentWirelessDevice {
            return String(localized: "Connected — \(device)")
        }
        return String(localized: "Connected to tablet")
    }

    private var detail: String {
        let size = settings.resolutionSize
        var resolution = "\(size.width) × \(size.height)"
        if settings.hiDPI { resolution += " HiDPI" }
        return [resolution, String(localized: "\(settings.effectiveRefreshRate) Hz")].joined(separator: " · ")
    }

    private var mode: Binding<ConnectionMode> {
        Binding(
            get: { settings.connectionMode },
            set: { newValue in
                // Same guard as the old menu: re-picking the current mode must
                // not restart the server.
                if newValue != settings.connectionMode { settings.connectionMode = newValue }
            }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: "SharpScreen")
                        .font(.system(size: 13, weight: .semibold))
                    HStack(spacing: 5) {
                        Circle().fill(statusColor).frame(width: 7, height: 7)
                        Text(statusText)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Toggle("", isOn: Binding(
                    get: { settings.isRunning },
                    set: { _ in actions.toggleServer() }
                ))
                .toggleStyle(.switch)
                .labelsHidden()
                .disabled(!settings.isRunning && !settings.hasScreenRecordingPermission)
                .help(settings.isRunning ? Text("Stop Streaming") : Text("Start Streaming"))
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 10)

            PanelDivider()

            VStack(alignment: .leading, spacing: 8) {
                Text("Connection Mode")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
                ModeSegmentedControl(selection: mode, width: 272)
                if settings.connectionMode == .usb, settings.usbDevices.count > 1 {
                    // One tablet streams at a time (issue #3).
                    Picker("Tablet", selection: Binding(
                        get: { settings.usbDevices.first(where: \.isActive)?.serial ?? "" },
                        set: { settings.onSelectUsbDevice?($0) }
                    )) {
                        ForEach(settings.usbDevices) { device in
                            Text(verbatim: device.name).tag(device.serial)
                        }
                    }
                    .pickerStyle(.menu)
                    .font(.system(size: 12))
                }
                Text(detail)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            PanelDivider()

            VStack(spacing: 0) {
                PanelRow(title: "Settings…", shortcut: "⌘S", action: actions.showSettings)
                PanelRow(title: "Quit SharpScreen", shortcut: "⌘Q", action: actions.quit)
            }
            .padding(5)
        }
        .frame(width: 300)
        .fixedSize(horizontal: false, vertical: true)
    }
}

private struct PanelDivider: View {
    var body: some View {
        Rectangle()
            .fill(Color.primary.opacity(0.1))
            .frame(height: 1)
            .padding(.horizontal, 14)
    }
}

/// A menu-like row: highlights on hover, shortcut on the right.
private struct PanelRow: View {
    let title: LocalizedStringKey
    let shortcut: String
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).font(.system(size: 13))
                Spacer()
                Text(verbatim: shortcut)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
            .background {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(hovering ? Color.primary.opacity(0.1) : .clear)
            }
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}
