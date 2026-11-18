import Cocoa
import SwiftUI

// MARK: - Palette

extension Color {
    /// SharpScreen accent pink (#D4537E): tab selection, selected rows and
    /// presets, prominent buttons.
    static let sharpAccent = Color(red: 0xD4 / 255.0, green: 0x53 / 255.0, blue: 0x7E / 255.0)
}

// MARK: - Tabs

enum SettingsTab: String, CaseIterable, Identifiable {
    case connection, display, streaming, general, about

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .connection: return "Connection"
        case .display: return "Display"
        case .streaming: return "Streaming"
        case .general: return "General"
        case .about: return "About"
        }
    }

    var symbol: String {
        switch self {
        case .connection: return "ipad.landscape"
        case .display: return "display"
        case .streaming: return "gauge"
        case .general: return "gearshape"
        case .about: return "info.circle"
        }
    }
}

/// macOS-settings-style toolbar: SF Symbol over a short label. Every item has
/// the same fixed width so the icons are evenly spaced whatever the label
/// length (Korean and English labels differ a lot).
struct SettingsTabBar: View {
    @Binding var selection: SettingsTab
    static let itemWidth: CGFloat = 76

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 2) {
                ForEach(SettingsTab.allCases) { tab in
                    SettingsTabItem(tab: tab, isSelected: selection == tab) {
                        selection = tab
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 6)
            .padding(.bottom, 8)

            Rectangle()
                .fill(Color.primary.opacity(0.1))
                .frame(height: 1)
        }
    }
}

private struct SettingsTabItem: View {
    let tab: SettingsTab
    let isSelected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: tab.symbol)
                    .font(.system(size: 18, weight: .regular))
                    .frame(height: 22)
                Text(tab.title)
                    .font(.system(size: 11, weight: isSelected ? .semibold : .regular))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundColor(isSelected ? .sharpAccent : .secondary)
            .frame(width: SettingsTabBar.itemWidth, height: 48)
            .background {
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(Color.primary.opacity(isSelected ? 0.07 : (hovering ? 0.04 : 0)))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Grouped sections

/// Fill of a grouped container: lifted white in light mode, a faint white
/// wash in dark mode — the System Settings look.
struct GroupFill: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(colorScheme == .dark ? Color.white.opacity(0.05) : Color.white.opacity(0.75))
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Color.primary.opacity(colorScheme == .dark ? 0.12 : 0.1), lineWidth: 0.5)
            }
    }
}

/// A small secondary caption over a rounded container of rows.
struct SettingsSection<Content: View, Trailing: View>: View {
    let title: LocalizedStringKey?
    var footer: LocalizedStringKey?
    @ViewBuilder let content: Content
    @ViewBuilder let trailing: Trailing

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title {
                HStack {
                    Text(title)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.secondary)
                    Spacer()
                    trailing
                }
                .padding(.horizontal, 4)
            }
            VStack(alignment: .leading, spacing: 0) {
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GroupFill())
            if let footer {
                Text(footer)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
            }
        }
    }
}

extension SettingsSection where Trailing == EmptyView {
    init(_ title: LocalizedStringKey? = nil, footer: LocalizedStringKey? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.footer = footer
        self.content = content()
        self.trailing = EmptyView()
    }
}

extension SettingsSection {
    init(_ title: LocalizedStringKey?, footer: LocalizedStringKey? = nil,
         @ViewBuilder content: () -> Content, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.footer = footer
        self.content = content()
        self.trailing = trailing()
    }
}

/// Label (and optional explanation) on the left, a control or value on the right.
struct SettingsRow<Accessory: View>: View {
    let title: LocalizedStringKey
    var subtitle: LocalizedStringKey?
    @ViewBuilder let accessory: Accessory

    init(_ title: LocalizedStringKey, subtitle: LocalizedStringKey? = nil, @ViewBuilder accessory: () -> Accessory) {
        self.title = title
        self.subtitle = subtitle
        self.accessory = accessory()
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13))
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            accessory
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Free-form padded content inside a section (lists, button rows, notes).
struct SettingsBlock<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            content
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Hairline between rows of a section, inset like System Settings.
struct RowDivider: View {
    var body: some View {
        Rectangle()
            .fill(Color.primary.opacity(0.08))
            .frame(height: 1)
            .padding(.leading, 12)
    }
}

/// Coloured callout for warnings inside a pane (permissions, setup issues).
struct NoticeBox<Content: View>: View {
    var tint: Color = .orange
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            content
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(tint.opacity(0.1))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(tint.opacity(0.25), lineWidth: 0.5)
        }
    }
}

// MARK: - Connection mode control

extension ConnectionMode {
    var title: LocalizedStringKey {
        switch self {
        case .usb: return "USB"
        case .wireless: return "Wireless"
        }
    }

    var localizedTitle: String {
        switch self {
        case .usb: return String(localized: "USB")
        case .wireless: return String(localized: "Wireless")
        }
    }

    var symbol: String {
        switch self {
        case .usb: return "cable.connector"
        case .wireless: return "wifi"
        }
    }
}

/// Segmented control whose selected segment is lifted *above* the track:
/// white with a shadow in light mode, a brighter wash in dark mode (the stock
/// segmented control renders it darker than the track there).
struct PillSegmentedControl<Value: Hashable>: View {
    struct Option {
        let value: Value
        let title: LocalizedStringKey
        var symbol: String?
    }

    @Binding var selection: Value
    let options: [Option]
    var width: CGFloat?
    var height: CGFloat = 24
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                let selected = selection == option.value
                Button(action: { selection = option.value }) {
                    HStack(spacing: 5) {
                        if let symbol = option.symbol {
                            Image(systemName: symbol)
                                .font(.system(size: 11, weight: .medium))
                        }
                        Text(option.title)
                            .font(.system(size: 12, weight: selected ? .semibold : .regular))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundColor(selected ? .primary : .secondary)
                    .frame(maxWidth: .infinity)
                    .frame(height: height)
                    .background {
                        if selected {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill(colorScheme == .dark ? Color.white.opacity(0.18) : Color.white)
                                .shadow(color: Color.black.opacity(colorScheme == .dark ? 0.3 : 0.12), radius: 1.5, y: 0.5)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(2)
        .frame(width: width)
        .background {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.primary.opacity(0.06))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        }
        .opacity(isEnabled ? 1 : 0.45)
    }
}

/// USB / Wireless switch (Connection pane).
struct ModeSegmentedControl: View {
    @Binding var selection: ConnectionMode
    var width: CGFloat = 240
    var showSymbols = true

    var body: some View {
        PillSegmentedControl(
            selection: $selection,
            options: ConnectionMode.allCases.map {
                .init(value: $0, title: $0.title, symbol: showSymbols ? $0.symbol : nil)
            },
            width: width
        )
    }
}

// MARK: - Rows and buttons

struct StatusRow: View {
    let title: LocalizedStringKey
    /// Already localized (or a raw value such as an address).
    let status: String
    let color: Color
    var hint: LocalizedStringKey?
    @State private var showHint = false
    @State private var hovering = false

    var body: some View {
        HStack {
            Text(title)
                .font(.system(size: 13))
            if let hint = hint {
                Button(action: { showHint.toggle() }) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 11))
                        .foregroundColor(hovering ? .sharpAccent : .secondary)
                        .frame(width: 18, height: 18)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onHover { hovering = $0 }
                .help(hint)
                .popover(isPresented: $showHint, arrowEdge: .top) {
                    Text(hint)
                        .font(.system(size: 12))
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(width: 280, alignment: .leading)
                        .padding(12)
                }
            }
            Spacer()
            HStack(spacing: 6) {
                Circle()
                    .fill(color)
                    .frame(width: 6, height: 6)
                Text(verbatim: status)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(color)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

struct ResolutionRow: View {
    let resolution: String
    let isSelected: Bool
    let action: () -> Void
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack {
                Text(verbatim: resolution.replacingOccurrences(of: "x", with: " × "))
                    .font(.system(size: 12))
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(isSelected ? Color.sharpAccent : (isHovered ? Color.primary.opacity(0.05) : Color.clear))
            .foregroundColor(isSelected ? .white : .primary)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}

struct BitrateButton: View {
    let label: String
    let value: Int
    let currentValue: Int
    let disabled: Bool
    let action: () -> Void
    @State private var isHovered = false

    var isSelected: Bool { currentValue == value }

    var body: some View {
        Button(action: action) {
            Text(verbatim: label)
                .font(.system(size: 11, weight: isSelected ? .semibold : .regular))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .background {
                    if isSelected {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(Color.sharpAccent)
                    } else {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(Color.primary.opacity(isHovered && !disabled ? 0.08 : 0.04))
                            .overlay {
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .strokeBorder(Color.primary.opacity(0.1), lineWidth: 0.5)
                            }
                    }
                }
                .foregroundColor(isSelected ? .white : (disabled ? .secondary : .primary))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.5 : 1)
        .onHover { isHovered = $0 }
    }
}

struct RotationButton: View {
    let degrees: Int
    let label: String
    let isSelected: Bool
    let action: () -> Void
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                RoundedRectangle(cornerRadius: 2)
                    .stroke(isSelected ? Color.sharpAccent : Color.secondary.opacity(0.5), lineWidth: 1)
                    .frame(width: degrees == 90 || degrees == 270 ? 16 : 24, height: degrees == 90 || degrees == 270 ? 24 : 16)

                Text(verbatim: "\(label)°")
                    .font(.system(size: 9))
                    .foregroundColor(isSelected ? .sharpAccent : .secondary)
            }
            .frame(width: 50, height: 40)
            .background {
                if isSelected {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Color.sharpAccent.opacity(0.15))
                        .overlay {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .strokeBorder(Color.sharpAccent, lineWidth: 1)
                        }
                } else {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Color.primary.opacity(isHovered ? 0.08 : 0.04))
                        .overlay {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .strokeBorder(Color.primary.opacity(0.1), lineWidth: 0.5)
                        }
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}

/// Small "Open System Settings" style button with a gear.
struct SystemSettingsButton: View {
    let title: LocalizedStringKey
    let url: String

    var body: some View {
        Button(action: {
            if let url = URL(string: url) {
                NSWorkspace.shared.open(url)
            }
        }) {
            HStack(spacing: 4) {
                Image(systemName: "gear")
                Text(title)
            }
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.small)
    }
}
