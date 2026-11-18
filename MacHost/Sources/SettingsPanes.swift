import Cocoa
import SwiftUI

private var isMacOS26OrLater: Bool {
    ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 26
}

// MARK: - Connection

struct ConnectionPane: View {
    @ObservedObject var settings: DisplaySettings

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ConnectionStatusCard(settings: settings)

            HStack {
                Spacer()
                ModeSegmentedControl(selection: Binding(
                    get: { settings.connectionMode },
                    // Re-clicking the current mode must not restart the server.
                    set: { mode in
                        if mode != settings.connectionMode {
                            settings.connectionMode = mode
                        }
                    }
                ))
                Spacer()
            }

            if settings.connectionMode == .usb {
                usbSection
            } else {
                wirelessStatusSection
                WirelessSection(settings: settings,
                                pairedDeviceStore: (NSApp.delegate as? AppDelegate)?.pairedDeviceStore ?? PairedDeviceStore())
            }

            networkSection
        }
    }

    private var usbSection: some View {
        let port = String(settings.port)
        return SettingsSection("USB Setup",
                               footer: "Connect the tablet with a USB cable and allow USB debugging when asked. SharpScreen sets up adb reverse automatically when you click Start.") {
            StatusRow(title: "ADB installed",
                      status: settings.adbInstalled ? String(localized: "Installed") : String(localized: "Missing"),
                      color: settings.adbInstalled ? .green : .red,
                      hint: "USB mode tunnels the TCP stream through the cable using `adb reverse`. Requires the `adb` command on the Mac. Searched paths: Homebrew, /usr/local/bin, ~/Library/Android/sdk/platform-tools, and PATH (`which adb`).")
            if !settings.adbInstalled {
                SettingsBlock {
                    Text("Install it with Homebrew:")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    Text(verbatim: "brew install android-platform-tools")
                        .font(.system(size: 11, design: .monospaced))
                        .padding(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.primary.opacity(0.06))
                        .cornerRadius(5)
                        .textSelection(.enabled)
                }
                .padding(.top, -6)
            }
            RowDivider()
            StatusRow(title: "ADB reverse",
                      status: settings.adbReverseConfigured ? String(localized: "OK") : String(localized: "Pending"),
                      color: settings.adbReverseConfigured ? .green : .orange,
                      hint: "Whether `adb reverse tcp:\(port) tcp:\(port)` is currently configured. The Mac app sets this up automatically when you click Start. Goes green within ~2 seconds after the tablet is plugged in and authorized.")
            RowDivider()
            StatusRow(title: "USB device",
                      status: settings.usbDeviceConnected ? String(localized: "Detected") : String(localized: "Not detected"),
                      color: settings.usbDeviceConnected ? .green : .red,
                      hint: "An Android device authorized for ADB and visible to your Mac. Plug in via USB-C and tap Allow on the device's USB debugging prompt.")
        }
    }

    private var wirelessStatusSection: some View {
        SettingsSection("Network Status") {
            StatusRow(title: "Wi-Fi",
                      status: settings.wifiConnected ? String(localized: "Connected") : String(localized: "Disconnected"),
                      color: settings.wifiConnected ? .green : .red,
                      hint: "Whether the Mac currently has a working internet route. Wireless mode requires the Mac to be on a Wi-Fi (or Ethernet) network — the same network the tablet is on.")
            RowDivider()
            StatusRow(title: "Listening on",
                      status: settings.listeningAddress.map { "\($0):\(settings.port)" } ?? "—",
                      color: settings.listeningAddress != nil ? .green : .secondary,
                      hint: "The LAN address the tablet must reach. The QR code embeds this exact host:port — if it changes (e.g. you switch Wi-Fi), re-scan the new QR on the tablet.")
        }
    }

    private var networkSection: some View {
        SettingsSection("Network") {
            SettingsRow("Server Port") {
                // No digit grouping: a port reads "54321", not "54,321".
                TextField("Port", value: $settings.port, format: .number.grouping(.never))
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 80)
                    .disabled(settings.isRunning)
            }
            if settings.isRunning {
                portNote("Stop the server to change the port.", color: .orange)
            } else if settings.connectionMode == .wireless {
                portNote("Changing the port invalidates existing pairings — re-scan the QR on each tablet.", color: .secondary)
            } else if settings.port != 54321 {
                portNote("Custom port set — the Android app must use the same port.", color: .secondary)
            }
        }
    }

    private func portNote(_ text: LocalizedStringKey, color: Color) -> some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundColor(color)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 12)
            .padding(.bottom, 9)
            .padding(.top, -4)
    }
}

/// Status at a glance plus the Start/Stop button.
struct ConnectionStatusCard: View {
    @ObservedObject var settings: DisplaySettings

    private var dotColor: Color {
        if settings.isRunning {
            return settings.clientConnected ? .green : .orange
        }
        return .gray
    }

    @ViewBuilder private var title: some View {
        if settings.isRunning && settings.clientConnected {
            if let name = settings.currentWirelessDevice {
                Text("Streaming · \(name)")
            } else {
                Text("Streaming to tablet")
            }
        } else if settings.isRunning {
            Text("Waiting for tablet")
        } else {
            Text("Stopped")
        }
    }

    private var subtitle: String {
        let size = settings.resolutionSize
        var resolution = "\(size.width) × \(size.height)"
        if settings.hiDPI { resolution += " HiDPI" }
        var parts = [
            settings.connectionMode.localizedTitle,
            resolution,
            String(localized: "\(settings.effectiveRefreshRate) Hz")
        ]
        if settings.isRunning {
            parts.append(String(localized: "Port \(String(settings.port))"))
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(dotColor)
                .frame(width: 10, height: 10)
                .overlay {
                    Circle()
                        .stroke(dotColor.opacity(0.3), lineWidth: 3)
                        .scaleEffect(1.6)
                        .opacity(settings.isRunning ? 1 : 0)
                }
                .padding(.horizontal, 4)

            VStack(alignment: .leading, spacing: 3) {
                title
                    .font(.system(size: 15, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(verbatim: subtitle)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                if !settings.hasScreenRecordingPermission && !settings.isRunning {
                    Text("Screen Recording permission required — see General.")
                        .font(.system(size: 11))
                        .foregroundColor(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Spacer(minLength: 8)

            Button(action: {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                    settings.toggleServer()
                }
            }) {
                HStack(spacing: 5) {
                    Image(systemName: settings.isRunning ? "stop.fill" : "play.fill")
                        .font(.system(size: 11))
                    if settings.isRunning {
                        Text("Stop")
                    } else {
                        Text("Start")
                    }
                }
                .font(.system(size: 13, weight: .medium))
                .frame(minWidth: 64)
            }
            .buttonStyle(.borderedProminent)
            .tint(settings.isRunning ? .red : .sharpAccent)
            .controlSize(.large)
            .disabled(!settings.hasScreenRecordingPermission)
        }
        .padding(14)
        .background(GroupFill())
    }
}

// MARK: - Display

struct DisplayPane: View {
    @ObservedObject var settings: DisplaySettings
    // Plain strings for the custom resolution fields: TextField(value:format:)
    // only commits on Return/focus-loss, so clicking Apply read stale values,
    // and .number formatting injected locale grouping separators ("1,200").
    @State private var customWidthText = ""
    @State private var customHeightText = ""

    private var customWidthValue: Int? { Int(customWidthText.trimmingCharacters(in: .whitespaces)) }
    private var customHeightValue: Int? { Int(customHeightText.trimmingCharacters(in: .whitespaces)) }
    private var customResolutionValid: Bool {
        guard let w = customWidthValue, let h = customHeightValue else { return false }
        return DisplaySettings.isValidCustomResolution(width: w, height: h)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            resolutionSection
            orientationSection
            frameRateSection
        }
    }

    // MARK: Resolution

    private var resolutionSection: some View {
        SettingsSection("Resolution", content: {
            SettingsBlock {
                resolutionList

                if settings.showAllResolutions {
                    HStack(spacing: 8) {
                        Text("Custom")
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                        Spacer()
                        TextField("W", text: $customWidthText)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 70)
                        Text(verbatim: "×")
                            .foregroundColor(.secondary)
                        TextField("H", text: $customHeightText)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 70)
                        Button("Apply") {
                            guard customResolutionValid,
                                  let w = customWidthValue,
                                  let h = customHeightValue else { return }
                            settings.customWidth = w
                            settings.customHeight = h
                            settings.applyCustomResolution()
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .disabled(!customResolutionValid)
                    }
                    .onAppear {
                        customWidthText = String(settings.customWidth)
                        customHeightText = String(settings.customHeight)
                    }
                    if !customResolutionValid {
                        Text("Supported range: 640–7680 × 480–4320")
                            .font(.system(size: 11))
                            .foregroundColor(.orange)
                    }
                }
            }
            RowDivider()
            SettingsRow("HiDPI (Retina)", subtitle: "Renders at 2× resolution for sharper text. Increases bandwidth.") {
                Toggle("", isOn: $settings.hiDPI)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
                    .disabled(settings.isRunning)
            }
        }, trailing: {
            Toggle("Show all", isOn: $settings.showAllResolutions)
                .toggleStyle(.switch)
                .controlSize(.mini)
                .font(.system(size: 11))
        })
    }

    private var resolutionList: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if settings.showAllResolutions {
                    // Custom (Apply) values aren't in any preset group —
                    // surface them so the selection is visible in the list.
                    if !DisplaySettings.allResolutions.contains(settings.resolution) {
                        groupHeader(Text("Custom"), Text("User defined"))
                        ResolutionRow(resolution: settings.resolution, isSelected: true) {}
                    }
                    ForEach(DisplaySettings.resolutionGroups) { group in
                        groupHeader(Text(verbatim: group.name), Text(LocalizedStringKey(group.ratio)))
                        ForEach(group.resolutions, id: \.self) { res in
                            ResolutionRow(resolution: res, isSelected: settings.resolution == res) {
                                settings.resolution = res
                            }
                        }
                    }
                } else {
                    ForEach(DisplaySettings.commonResolutions, id: \.self) { res in
                        ResolutionRow(resolution: res, isSelected: settings.resolution == res) {
                            settings.resolution = res
                        }
                    }
                    // Current selection from the full list or a custom
                    // Apply — keep it visible in the compact list too.
                    if !DisplaySettings.commonResolutions.contains(settings.resolution) {
                        ResolutionRow(resolution: settings.resolution, isSelected: true) {}
                    }
                }
            }
        }
        .frame(height: settings.showAllResolutions ? 180 : 140)
        .background(Color.primary.opacity(0.03))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    private func groupHeader(_ name: Text, _ ratio: Text) -> some View {
        HStack(spacing: 6) {
            name.font(.system(size: 11, weight: .semibold))
            ratio.font(.system(size: 10)).foregroundColor(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.04))
    }

    // MARK: Orientation

    private var isPortrait: Bool { settings.rotation == 90 || settings.rotation == 270 }

    private var orientationSection: some View {
        SettingsSection("Orientation") {
            SettingsBlock {
                HStack(spacing: 16) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 4)
                            .stroke(Color.sharpAccent.opacity(0.6), lineWidth: 1)
                            .frame(width: 80, height: 50)
                            .scaleEffect(x: settings.flipHorizontal ? -1 : 1, y: settings.flipVertical ? -1 : 1)
                            .rotationEffect(.degrees(Double(settings.rotation)))

                        Group {
                            if isPortrait {
                                Text("Portrait")
                            } else {
                                Text("Landscape")
                            }
                        }
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                    }
                    .frame(width: 100, height: 84)

                    VStack(alignment: .leading, spacing: 6) {
                        Text("Rotation")
                            .font(.system(size: 13))
                        HStack(spacing: 6) {
                            ForEach([0, 90, 180, 270], id: \.self) { degrees in
                                RotationButton(degrees: degrees, label: "\(degrees)", isSelected: settings.rotation == degrees) {
                                    settings.rotation = degrees
                                }
                            }
                        }
                        if isPortrait {
                            Text("Display will be in portrait mode")
                                .font(.system(size: 11))
                                .foregroundColor(.sharpAccent)
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
            RowDivider()
            SettingsRow("Flip Horizontally", subtitle: "Mirror left and right") {
                Toggle("", isOn: $settings.flipHorizontal)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
            }
            RowDivider()
            SettingsRow("Flip Vertically", subtitle: "Mirror top and bottom") {
                Toggle("", isOn: $settings.flipVertical)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
            }
            RowDivider()
            SettingsRow("Display Arrangement", subtitle: "Position the tablet relative to your other displays.") {
                Button(action: {
                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.displays?displayArrangement")!)
                }) {
                    HStack(spacing: 4) {
                        Image(systemName: "rectangle.connected.to.line.below")
                        Text("Arrange Displays…")
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
    }

    // MARK: Frame rate

    private var frameRateSection: some View {
        SettingsSection("Frame Rate") {
            SettingsBlock {
                HStack {
                    Text("Refresh Rate")
                        .font(.system(size: 13))
                    Spacer()
                    Text("\(settings.refreshRate) Hz")
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundColor(.sharpAccent)
                }

                HStack(spacing: 6) {
                    ForEach([30, 60, 90, 120], id: \.self) { rate in
                        BitrateButton(
                            label: "\(rate)",
                            value: rate,
                            currentValue: settings.refreshRate,
                            disabled: false
                        ) {
                            settings.refreshRate = rate
                        }
                    }
                }

                // Custom rate (issue #41): budget tablets can be
                // artifact-free below a preset but not at it —
                // let users find their panel's sweet spot.
                HStack(spacing: 8) {
                    Text("Custom")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    Slider(
                        value: Binding(
                            get: { Double(settings.refreshRate) },
                            set: {
                                let rate = Int($0.rounded())
                                // Same-value stores still fire didSet +
                                // UserDefaults writes — skip them.
                                if rate != settings.refreshRate {
                                    settings.refreshRate = rate
                                }
                            }
                        ),
                        in: 15...120,
                        step: 1
                    )
                }

                if settings.refreshRate >= 90 {
                    Text("High refresh rate for a smooth experience")
                        .font(.system(size: 11))
                        .foregroundColor(.green)
                } else if ![30, 60].contains(settings.refreshRate) {
                    Text("Custom rate — handy when a tablet shows artifacts above a certain FPS")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

// MARK: - Streaming

struct StreamingPane: View {
    @ObservedObject var settings: DisplaySettings

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            gamingSection
            bitrateSection
            qualitySection
            touchSection
            if settings.clientConnected {
                performanceSection
            }
        }
    }

    private var gamingSection: some View {
        SettingsSection("Gaming Mode") {
            SettingsRow("Enable Gaming Mode", subtitle: "Optimized for competitive gaming") {
                Toggle("", isOn: $settings.gamingBoost)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
            }
            if settings.gamingBoost {
                RowDivider()
                SettingsBlock {
                    featureLine("High bitrate (1000 Mbps)")
                    featureLine("120 Hz refresh rate")
                    featureLine("Ultra-low latency encoding")
                }
            }
        }
    }

    private func featureLine(_ text: LocalizedStringKey) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
                .font(.system(size: 11))
            Text(text)
                .font(.system(size: 12))
                .foregroundColor(.secondary)
        }
    }

    private var bitrateSection: some View {
        SettingsSection("Bitrate") {
            SettingsBlock {
                HStack {
                    Text("Target bitrate")
                        .font(.system(size: 13))
                    Spacer()
                    Text("\(String(settings.effectiveBitrate)) Mbps")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundColor(.sharpAccent)
                }

                HStack(spacing: 6) {
                    ForEach([100, 300, 500, 1000, 2000], id: \.self) { value in
                        BitrateButton(label: "\(value)", value: value, currentValue: settings.bitrate, disabled: settings.gamingBoost) {
                            settings.bitrate = value
                        }
                    }
                }

                HStack(spacing: 8) {
                    Text(verbatim: "20")
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                    Slider(value: Binding(
                        get: { Double(settings.bitrate) },
                        set: { settings.bitrate = Int($0) }
                    ), in: 20...5000, step: 10)
                    .disabled(settings.gamingBoost)
                    Text(verbatim: "5000")
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                }

                if settings.gamingBoost {
                    HStack(spacing: 4) {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 10))
                        Text("Locked at 1000 Mbps in Gaming Mode")
                            .font(.system(size: 11))
                    }
                    .foregroundColor(.orange)
                }
            }
        }
    }

    private var qualitySection: some View {
        SettingsSection("Quality") {
            SettingsBlock {
                PillSegmentedControl(selection: $settings.quality, options: [
                    .init(value: "ultralow", title: "Ultra Low"),
                    .init(value: "low", title: "Low"),
                    .init(value: "medium", title: "Medium"),
                    .init(value: "high", title: "High")
                ])
                .disabled(settings.gamingBoost)

                if settings.gamingBoost {
                    Text("Quality locked to Ultra Low in Gaming Mode")
                        .font(.system(size: 11))
                        .foregroundColor(.orange)
                } else if settings.quality == "ultralow" {
                    Text("Fastest encoding, lowest latency")
                        .font(.system(size: 11))
                        .foregroundColor(.green)
                }
            }
        }
    }

    private var touchSection: some View {
        SettingsSection("Touch Input") {
            SettingsRow("Enable Touch Input", subtitle: "Control the Mac by touching the tablet") {
                Toggle("", isOn: $settings.touchEnabled)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
            }
            if !settings.touchEnabled {
                Text("Touch input is off — the tablet is display-only")
                    .font(.system(size: 11))
                    .foregroundColor(.orange)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 9)
                    .padding(.top, -4)
            }
        }
    }

    private var performanceSection: some View {
        SettingsSection("Performance") {
            SettingsBlock {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("FPS")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                        Text(verbatim: String(format: "%.1f", settings.currentFPS))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.green)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("Bitrate")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                        Text(verbatim: String(format: "%.1f Mbps", settings.currentBitrate))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.sharpAccent)
                    }
                }
            }
        }
    }
}

// MARK: - General

struct GeneralPane: View {
    @ObservedObject var settings: DisplaySettings
    @State private var daemonEnabled = false
    @State private var showResetConfirmation = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            startupSection
            permissionsSection
            appSection
        }
    }

    private var startupSection: some View {
        SettingsSection("Startup") {
            if #available(macOS 13.0, *) {
                SettingsRow("Launch at Login", subtitle: "Run SharpScreen in the background automatically after you log in.") {
                    Toggle("", isOn: Binding(
                        get: { daemonEnabled },
                        set: { newValue in
                            do {
                                if newValue {
                                    try DaemonManager.shared.enable()
                                } else {
                                    try DaemonManager.shared.disable()
                                }
                            } catch {
                                print("Daemon toggle failed: \(error)")
                            }
                            daemonEnabled = DaemonManager.shared.isEnabled
                        }
                    ))
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
                }
                RowDivider()
            }

            SettingsRow("Auto-start streaming on launch",
                        subtitle: "Start the server automatically when the app opens, so the tablet can connect without touching the Mac.") {
                Toggle("", isOn: $settings.autoStartStreamingOnLaunch)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
            }

            RowDivider()

            SettingsRow("Startup mode", subtitle: "Which connection mode to start in when auto-starting.") {
                ModeSegmentedControl(selection: $settings.startupMode, width: 150, showSymbols: false)
                    .disabled(!settings.autoStartStreamingOnLaunch)
            }
        }
        .onAppear {
            if #available(macOS 13.0, *) {
                daemonEnabled = DaemonManager.shared.isEnabled
            }
        }
    }

    private var captureMethodLabel: String {
        // The raw value is also matched on ("fallback"), so it stays English
        // in the model; only the placeholder is user-facing prose.
        settings.captureMethod == "Initializing..." ? String(localized: "Initializing…") : settings.captureMethod
    }

    private var permissionsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SettingsSection("Permissions & Status") {
                StatusRow(
                    title: isMacOS26OrLater ? "Screen & System Audio" : "Screen Recording",
                    status: settings.hasScreenRecordingPermission ? String(localized: "Granted") : String(localized: "Required"),
                    color: settings.hasScreenRecordingPermission ? .green : .red,
                    hint: "macOS privacy permission required to capture the virtual display. Grant it in System Settings → Privacy & Security → Screen Recording."
                )
                RowDivider()
                StatusRow(title: "Accessibility",
                          status: settings.hasAccessibilityPermission ? String(localized: "Granted") : String(localized: "Optional"),
                          color: settings.hasAccessibilityPermission ? .green : .orange,
                          hint: "Optional permission. Required only if you want touch input from the tablet to control the Mac. Streaming works without it.")
                RowDivider()
                StatusRow(title: "Virtual Display",
                          status: settings.displayCreated ? String(localized: "Active") : String(localized: "Inactive"),
                          color: settings.displayCreated ? .green : .secondary,
                          hint: "The macOS virtual display SharpScreen renders into. Created when you click Start; the tablet streams its pixels.")
                RowDivider()
                StatusRow(title: "Client Connected",
                          status: settings.clientConnected ? String(localized: "Yes") : String(localized: "No"),
                          color: settings.clientConnected ? .green : .secondary,
                          hint: "Whether the Android app currently has an active stream session.")
                if settings.isRunning {
                    RowDivider()
                    StatusRow(title: "Capture Method",
                              status: captureMethodLabel,
                              color: settings.captureMethod.contains("fallback") ? .orange : .green,
                              hint: "Which macOS API is currently capturing the virtual display. SCStream is the modern path; the CGDisplayStream fallback activates if SCStream fails (e.g. on certain virtual display configurations).")
                }
            }

            if !settings.hasScreenRecordingPermission {
                NoticeBox(tint: .orange) {
                    HStack(spacing: 6) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)
                        if isMacOS26OrLater {
                            Text("Screen & System Audio Recording Required")
                                .font(.system(size: 12, weight: .semibold))
                        } else {
                            Text("Screen Recording Required")
                                .font(.system(size: 12, weight: .semibold))
                        }
                    }
                    Group {
                        if isMacOS26OrLater {
                            Text("Required to capture the virtual display. Go to System Settings > Privacy & Security > Screen & System Audio Recording.")
                        } else {
                            Text("Required to capture the virtual display.")
                        }
                    }
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    SystemSettingsButton(title: "Open System Settings",
                                         url: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")
                }
            }

            if !settings.displaysHaveSeparateSpaces {
                NoticeBox(tint: .orange) {
                    HStack(spacing: 6) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)
                        Text("Tablet goes black in fullscreen apps")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    Text("\"Displays have separate Spaces\" is turned off, so macOS blanks every other display — including the tablet — whenever an app enters fullscreen. Turn it on in System Settings → Desktop & Dock → Mission Control, then log out and back in.")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    SystemSettingsButton(title: "Open System Settings",
                                         url: "x-apple.systempreferences:com.apple.Desktop-Settings.extension")
                }
            }

            if !settings.hasAccessibilityPermission {
                NoticeBox(tint: .blue) {
                    HStack(spacing: 6) {
                        Image(systemName: "hand.tap.fill")
                            .foregroundColor(.blue)
                        Text("Enable Touch Control")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    Text("Control your Mac from your tablet.")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    SystemSettingsButton(title: "Open Settings",
                                         url: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
                }
            }
        }
    }

    private var appSection: some View {
        SettingsSection("App") {
            SettingsRow("Reset Settings", subtitle: "Restore every setting to its default value.") {
                Button("Reset…") { showResetConfirmation = true }
                    .controlSize(.small)
            }
            RowDivider()
            SettingsRow("Restart SharpScreen") {
                Button("Restart") { restartApp() }
                    .controlSize(.small)
            }
            RowDivider()
            SettingsRow("Quit SharpScreen", subtitle: "Stops streaming and removes the virtual display.") {
                Button("Quit") { NSApp.terminate(nil) }
                    .controlSize(.small)
                    .help("Quit SharpScreen (⌘Q)")
            }
        }
        .alert("Reset Settings", isPresented: $showResetConfirmation) {
            Button("Cancel", role: .cancel) { }
            Button("Reset", role: .destructive) {
                settings.resetToDefaults()
                if let window = NSApp.windows.first(where: { $0.title == "SharpScreen" }) {
                    window.center()
                }
            }
        } message: {
            Text("This will reset all settings to default values.")
        }
    }

    /// Restart the app by launching a new instance and terminating current one
    private func restartApp() {
        // Get the app bundle path
        guard let appPath = Bundle.main.bundlePath as String? else {
            print("❌ Could not get app path")
            return
        }

        // Use Process to launch a new instance after a short delay
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/bin/sh")
        task.arguments = ["-c", "sleep 0.5 && open \"\(appPath)\""]

        do {
            try task.run()
            // Terminate current app
            NSApp.terminate(nil)
        } catch {
            print("❌ Failed to restart: \(error)")
        }
    }
}

// MARK: - About

struct AboutPane: View {
    @ObservedObject var updater: AppUpdater

    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: 88, height: 88)
                    .shadow(color: Color.black.opacity(0.2), radius: 6, y: 3)
                Text(verbatim: "SharpScreen")
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                Text("Version \(AppInfo.version)")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                    .textSelection(.enabled)
                Text("Turn your tablet into a second display")
                    .font(.system(size: 13, weight: .medium))
                    .padding(.top, 2)
                Text("Turns an Android tablet or phone into a second display for your Mac — over USB or Wi-Fi, with touch control. Free and open source.")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 24)
                Button("Check for Updates…") {
                    AppUpdater.shared.checkForUpdates(nil)
                }
                .controlSize(.small)
                .disabled(!updater.canCheckForUpdates)
                .help(updater.canCheckForUpdates
                      ? "Look for a newer version now. SharpScreen also checks once a day."
                      : "Update checks are only available in the released build.")
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 8)

            SettingsSection("Links") {
                linkRow("GitHub", symbol: "chevron.left.forwardslash.chevron.right", url: AppInfo.github)
                RowDivider()
                linkRow("Releases", symbol: "shippingbox", url: AppInfo.releases)
                RowDivider()
                linkRow("Report a Problem", symbol: "exclamationmark.bubble", url: AppInfo.reportProblem)
            }

            // SharpScreen is a hard fork; credit the project it is built on.
            SettingsSection("Credits") {
                linkRow("Fork of Side Screen by Tran Vuong Quoc Dat (MIT)", symbol: "arrow.triangle.branch", url: AppInfo.upstream)
            }
        }
    }

    private func linkRow(_ title: LocalizedStringKey, symbol: String, url: URL) -> some View {
        Link(destination: url) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 12))
                    .foregroundColor(.sharpAccent)
                    .frame(width: 18)
                Text(title)
                    .font(.system(size: 13))
                    .foregroundColor(.primary)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Wireless Section

struct WirelessSection: View {
    @ObservedObject var settings: DisplaySettings
    let pairedDeviceStore: PairedDeviceStore
    @State private var qrImage: NSImage?
    @State private var pairedDevices: [PairedDevice] = []
    @State private var showResetConfirm = false
    /// Used to force the relative-time labels to recompute every tick even when
    /// the underlying lastConnected timestamp hasn't changed (e.g. while a
    /// device is disconnected and we still want "5 minutes ago" to count up).
    @State private var nowTick: Date = Date()
    /// AWDL (AirDrop, Handoff, Universal Control) up: the Mac's Wi-Fi leaves its
    /// channel about twice a second (docs/measurements.md, "Wi-Fi").
    @State private var awdlActive = AWDLStatus.isActive()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if awdlActive {
                NoticeBox(tint: .orange) {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "wifi.exclamationmark")
                            .foregroundColor(.orange)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("AirDrop, Handoff and Universal Control can make wireless streaming stutter.")
                                .font(.system(size: 11, weight: .medium))
                                .fixedSize(horizontal: false, vertical: true)
                            Text("They briefly take this Mac's Wi-Fi off its channel about twice a second (~80 ms each). Turn them off while streaming, connect the Mac by Ethernet, or use USB. To turn them off until the next restart or AirDrop: sudo ifconfig awdl0 down")
                                .font(.system(size: 10))
                                .foregroundColor(.secondary)
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            SettingsSection("Pair Device") {
                SettingsBlock {
                    VStack(spacing: 8) {
                        if !settings.isRunning {
                            HStack(spacing: 6) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.orange)
                                Text("Click Start above to begin listening, then scan the QR.")
                                    .font(.system(size: 11))
                                    .foregroundColor(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 0)
                            }
                            .padding(8)
                            .background(Color.orange.opacity(0.12))
                            .cornerRadius(6)
                        }
                        if let qr = qrImage {
                            Image(nsImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 180, height: 180)
                                .padding(8)
                                .background(Color.white)
                                .cornerRadius(8)
                        } else {
                            Text("Generating QR…").foregroundColor(.secondary)
                        }
                        Text("Scan this QR from SharpScreen for Android (Wireless tab)")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                        Group {
                            if let ip = LANAddressResolver.primaryIPv4() {
                                Text("Listening: \(ip):\(String(settings.port))")
                            } else {
                                Text("Wi-Fi disconnected — no LAN address")
                            }
                        }
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(.secondary)
                        if let code = settings.pairingCode {
                            HStack(spacing: 10) {
                                VStack { Divider() }
                                Text("No camera on your tablet?")
                                    .font(.system(size: 11))
                                    .foregroundColor(.secondary)
                                    .fixedSize()
                                VStack { Divider() }
                            }
                            .padding(.top, 8)
                            Text(verbatim: PairingCode.display(code))
                                .font(.system(size: 28, weight: .bold, design: .monospaced))
                                .kerning(3)
                                .textSelection(.enabled)
                            Text("Type this one-time code in the Android app instead.\nA new code is issued after each pairing.")
                                .font(.system(size: 11))
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }

            SettingsSection("Paired Devices (\(pairedDevices.count))", content: {
                if pairedDevices.isEmpty {
                    SettingsBlock {
                        Text("No devices paired yet.")
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                    }
                } else {
                    ForEach(Array(pairedDevices.enumerated()), id: \.element.name) { index, device in
                        let isLive = settings.currentWirelessDevice == device.name
                        if index > 0 { RowDivider() }
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: device.name).font(.system(size: 13))
                                HStack(spacing: 4) {
                                    Circle()
                                        .fill(isLive ? Color.green : Color.secondary)
                                        .frame(width: 6, height: 6)
                                    Group {
                                        if isLive {
                                            Text("Connected")
                                        } else {
                                            Text(verbatim: relativeTimeString(from: device.lastConnected, to: nowTick))
                                        }
                                    }
                                    .font(.system(size: 11))
                                    .foregroundColor(isLive ? .green : .secondary)
                                }
                            }
                            Spacer()
                            Button("Forget") {
                                pairedDeviceStore.forget(name: device.name)
                                refreshPaired()
                            }
                            .controlSize(.small)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                    }
                }
                RowDivider()
                SettingsRow("Reset Pairing Token", subtitle: "Forgets every paired device.") {
                    Button("Reset…") {
                        showResetConfirm = true
                    }
                    .controlSize(.small)
                    .foregroundColor(.red)
                }
            }, trailing: {
                Button(action: {
                    nowTick = Date()
                    refreshPaired()
                }) {
                    Image(systemName: "arrow.counterclockwise")
                        .font(.system(size: 11, weight: .semibold))
                }
                .buttonStyle(.borderless)
                .help("Refresh list and timestamps")
            })
        }
        .onAppear {
            refreshQR()
            refreshPaired()
            nowTick = Date()
            awdlActive = AWDLStatus.isActive()
        }
        // One-parameter onChange(of:perform:) works on macOS 13+. The
        // two-parameter form requires macOS 14 and would block Ventura.
        // Deprecation is a compile-time warning only on Xcode 15+ SDKs.
        .onChange(of: settings.port) { _ in refreshQR() }
        .onReceive(Timer.publish(every: 5, on: .main, in: .common).autoconnect()) { now in
            nowTick = now
            refreshPaired()
            awdlActive = AWDLStatus.isActive()
        }
        .alert("Reset Token?", isPresented: $showResetConfirm) {
            Button("Cancel", role: .cancel) { }
            Button("Reset", role: .destructive) {
                _ = WirelessAuth.reset()
                pairedDeviceStore.clear()
                refreshQR()
                refreshPaired()
            }
        } message: {
            Text("This will disconnect all paired devices. They will need to scan the new QR to connect again.")
        }
    }

    private func refreshQR() {
        let token = WirelessAuth.loadOrCreate()
        let host = LANAddressResolver.primaryIPv4() ?? "0.0.0.0"
        let name = Host.current().localizedName ?? "Mac"
        let url = PairingURL.build(host: host, port: settings.port, token: token, name: name)
        qrImage = QRRenderer.render(url: url, size: 180)
    }

    private func refreshPaired() {
        pairedDevices = pairedDeviceStore.all()
    }

    /// The locale the UI is actually shown in (the bundle's best match for the
    /// user's languages), so dates never mix languages with the strings.
    private static let uiLocale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "en")

    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = uiLocale
        formatter.unitsStyle = .full
        formatter.dateTimeStyle = .numeric
        return formatter
    }()

    private func relativeTimeString(from past: Date, to now: Date) -> String {
        let elapsed = max(0, now.timeIntervalSince(past))
        if elapsed < 30 { return String(localized: "just now") }
        // Whole units, same buckets as before: seconds < 1 min < minutes
        // < 1 h < hours < 1 day < days.
        let rounded: TimeInterval
        if elapsed < 60 {
            rounded = elapsed.rounded(.down)
        } else if elapsed < 3600 {
            rounded = (elapsed / 60).rounded(.down) * 60
        } else if elapsed < 86400 {
            rounded = (elapsed / 3600).rounded(.down) * 3600
        } else {
            rounded = (elapsed / 86400).rounded(.down) * 86400
        }
        return Self.relativeFormatter.localizedString(fromTimeInterval: -rounded)
    }
}
