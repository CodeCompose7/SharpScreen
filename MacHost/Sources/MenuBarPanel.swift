import AppKit
import SwiftUI

/// The menu bar menu's content (right click or Control-click on the icon,
/// issue #7): status and the server switch, connection mode, Settings and
/// Quit. AppDelegate shows it inside an NSMenu.
struct MenuBarPanelView: View {
    struct Actions {
        var toggleServer: () -> Void
        var showSettings: () -> Void
        var quit: () -> Void
    }

    @ObservedObject var settings: DisplaySettings
    let actions: Actions

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
