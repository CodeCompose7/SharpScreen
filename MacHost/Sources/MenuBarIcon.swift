import AppKit

/// The menu bar glyph: the app icon's two panes in one colour. A landscape
/// pane at half opacity with a slightly tilted tablet pane over it, cut out
/// by a thin gap so the overlap reads without colour. A template image, so
/// macOS tints it for light and dark menu bars and dims it when disabled.
enum MenuBarIcon {
    static func image(pointSize: CGFloat = 18) -> NSImage {
        let size = NSSize(width: pointSize, height: pointSize)
        let image = NSImage(size: size, flipped: true) { _ in
            guard let ctx = NSGraphicsContext.current else { return false }
            let s = pointSize / 18 // drawn on an 18 pt grid

            // Landscape pane (the Mac display), upper left.
            let landscape = NSBezierPath(
                roundedRect: NSRect(x: 1.25 * s, y: 3.0 * s, width: 12.0 * s, height: 8.25 * s),
                xRadius: 2.2 * s, yRadius: 2.2 * s)
            NSColor.black.withAlphaComponent(0.5).setFill()
            landscape.fill()

            // Tablet pane, lower right, tilted clockwise like the app icon's.
            let tablet = NSBezierPath(
                roundedRect: NSRect(x: -3.3 * s, y: -4.6 * s, width: 6.6 * s, height: 9.2 * s),
                xRadius: 1.8 * s, yRadius: 1.8 * s)
            let place = AffineTransform(translationByX: 12.4 * s, byY: 11.4 * s)
            var turn = AffineTransform(rotationByDegrees: 9)
            turn.append(place)
            tablet.transform(using: turn)

            // Cut a thin gap around the tablet, then fill it solid.
            ctx.compositingOperation = .destinationOut
            NSColor.black.setStroke()
            tablet.lineWidth = 2.2 * s
            tablet.stroke()
            NSColor.black.setFill()
            tablet.fill()
            ctx.compositingOperation = .sourceOver
            NSColor.black.setFill()
            tablet.fill()
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "SharpScreen"
        return image
    }
}
