// Drags a large text window around the SharpScreen display, for measurements.
//
//   swiftc -O -o /tmp/drag_test scripts/drag_test.swift
//   /tmp/drag_test [move seconds] [hold seconds] [cycles]
//
// Moves for `move` s, holds still for `hold` s, `cycles` times (default: 12 s
// of motion, once), then closes the window. Only ever uses the display named
// "SharpScreen", never the Mac's own screens.
import AppKit

let args = CommandLine.arguments.dropFirst().compactMap(Double.init)
let move = args.count > 0 ? args[0] : 12
let hold = args.count > 1 ? args[1] : 0
let cycles = args.count > 2 ? Int(args[2]) : 1

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
guard let screen = NSScreen.screens.first(where: { $0.localizedName == "SharpScreen" }) else {
    print("no SharpScreen display")
    exit(1)
}
let size = NSSize(width: screen.frame.width * 0.6, height: screen.frame.height * 0.7)
let window = NSWindow(contentRect: NSRect(origin: screen.frame.origin, size: size), styleMask: [.titled], backing: .buffered, defer: false)
window.title = "drag test"
let scroll = NSScrollView(frame: NSRect(origin: .zero, size: size))
let text = NSTextView(frame: NSRect(origin: .zero, size: size))
text.string = (0..<400).map { "Line \($0): The quick brown fox jumps over the lazy dog — 다람쥐 헌 쳇바퀴에 타고파 0123456789" }.joined(separator: "\n")
text.font = .systemFont(ofSize: 13)
text.isEditable = false
text.isSelectable = false
scroll.documentView = text
window.contentView = scroll
window.level = .floating
window.orderFrontRegardless()

let start = Date()
Timer.scheduledTimer(withTimeInterval: 1.0 / 120.0, repeats: true) { timer in
    let elapsed = Date().timeIntervalSince(start)
    let period = move + hold
    let cycle = Int(elapsed / period)
    if cycle >= cycles {
        timer.invalidate()
        window.close()
        exit(0)
    }
    let inCycle = elapsed - Double(cycle) * period
    guard inCycle <= move else { return }
    let t = Double(cycle) * move + inCycle
    let x = screen.frame.minX + (screen.frame.width - size.width) * (0.5 + 0.5 * sin(t * 2.5))
    let y = screen.frame.minY + (screen.frame.height - size.height) * (0.5 + 0.5 * cos(t * 1.7))
    window.setFrameOrigin(NSPoint(x: x, y: y))
}
app.run()
