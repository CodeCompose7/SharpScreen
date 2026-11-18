import AppKit
import ScreenCaptureKit
import CoreMedia
import IOKit.pwr_mgt
import QuartzCore

/// Switches for measuring why the virtual display yields fewer than 120 new
/// frames a second. All off / at their stock values unless set with
/// `defaults write io.sharpscreen.mac <key> ...`; read when a stream starts.
enum CaptureDiagnostics {
    /// SCStreamConfiguration.queueDepth (stock 4, ScreenCaptureKit allows 3-8).
    static var queueDepth: Int {
        let v = UserDefaults.standard.integer(forKey: "diag.queueDepth")
        return v == 0 ? 4 : min(8, max(3, v))
    }

    /// Deliver ScreenCaptureKit samples on a serial queue instead of the
    /// concurrent global queue, so frames cannot be handled out of order.
    static var serialSampleQueue: Bool {
        UserDefaults.standard.bool(forKey: "diag.serialSampleQueue")
    }

    /// Show an animated pattern on the virtual display, driven by that
    /// display's own display link, to separate "WindowServer composites the
    /// virtual display slowly" from "the dragged window / cursor redraws slowly".
    static var motionPattern: Bool {
        UserDefaults.standard.bool(forKey: "diag.motionPattern")
    }

    /// Seconds between periodic keyframes (stock 1). diag.keyframeIntervalSec.
    static var keyframeIntervalSeconds: Double {
        let v = UserDefaults.standard.double(forKey: "diag.keyframeIntervalSec")
        return v > 0 ? v : 1.0
    }

    /// SCStreamConfiguration.minimumFrameInterval. Stock: 1/fps.
    /// diag.minFrameIntervalHz = 0 means no limit (CMTime.zero), N means 1/N s.
    static func minimumFrameInterval(fps: Int) -> CMTime {
        guard let hz = UserDefaults.standard.object(forKey: "diag.minFrameIntervalHz") as? Int else {
            return CMTime(value: 1, timescale: CMTimeScale(fps))
        }
        return hz <= 0 ? .zero : CMTime(value: 1, timescale: CMTimeScale(hz))
    }

    /// Capture the main (physical) display instead of the virtual one, to
    /// compare a real 120 Hz panel with CGVirtualDisplay's compositing rate.
    static var captureMainDisplay: Bool {
        UserDefaults.standard.bool(forKey: "diag.captureMainDisplay")
    }

    /// Capture 32BGRA instead of 420v (M1b needs RGB pixels for lossless tiles).
    static var captureBGRA: Bool { UserDefaults.standard.bool(forKey: "diag.captureBGRA") }

    /// diag.dumpFrames: every 2 s write the newest 32BGRA capture to a file
    /// (u32 width, u32 height, then packed rows) to compare against the tablet.
    static var dumpFrames: Bool { UserDefaults.standard.bool(forKey: "diag.dumpFrames") }

    static func dump(_ buffer: CVPixelBuffer, to path: String) {
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA else { return }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer), stride = CVPixelBufferGetBytesPerRow(buffer)
        var d = Data(capacity: 8 + w * h * 4)
        var header = [UInt32(w).littleEndian, UInt32(h).littleEndian]
        d.append(Data(bytes: &header, count: 8))
        for y in 0..<h { d.append(Data(bytes: base + y * stride, count: w * 4)) }
        try? d.write(to: URL(fileURLWithPath: path + ".tmp"))
        try? FileManager.default.removeItem(atPath: path)
        try? FileManager.default.moveItem(atPath: path + ".tmp", toPath: path)
    }

    /// diag.keepCaptureColorSpace: encode 32BGRA with ScreenCaptureKit's colour
    /// attachments untouched (the pre-fix behaviour), for comparison.
    static var keepCaptureColorSpace: Bool { UserDefaults.standard.bool(forKey: "diag.keepCaptureColorSpace") }

    /// Log SCStreamFrameInfo dirty rects / scale for the first frames.
    static var logDirtyRects: Bool { UserDefaults.standard.bool(forKey: "diag.logDirtyRects") }
    private static var loggedFrames = 0

    static func logFrameInfo(_ sample: CMSampleBuffer, _ image: CVPixelBuffer) {
        guard loggedFrames < 40 else { return }
        loggedFrames += 1
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let info = attachments.first else { return }
        let rects = (info[.dirtyRects] as? [NSDictionary] ?? []).compactMap { CGRect(dictionaryRepresentation: $0 as CFDictionary) }
        let content = (info[.contentRect] as? NSDictionary).flatMap { CGRect(dictionaryRepresentation: $0 as CFDictionary) }
        debugLog("FrameInfo: buffer \(CVPixelBufferGetWidth(image))x\(CVPixelBufferGetHeight(image)) fmt=\(CVPixelBufferGetPixelFormatType(image)) contentRect=\(content.map { NSStringFromRect($0) } ?? "-") contentScale=\(info[.contentScale] ?? "-") scaleFactor=\(info[.scaleFactor] ?? "-") dirty=\(rects.prefix(4).map { NSStringFromRect($0) })")
    }

    /// Capture with CGDisplayStream instead of ScreenCaptureKit, to tell a
    /// WindowServer compositing limit from a ScreenCaptureKit one.
    static var forceCGDisplayStream: Bool {
        UserDefaults.standard.bool(forKey: "diag.forceCGDisplayStream")
    }

    /// CGVirtualDisplaySettings has a `refreshDeadline` (double, default 0)
    /// that the public-ish header does not declare. Set diag.refreshDeadline
    /// to try values; applied through KVC only when the runtime has it.
    static func applyHiddenSettings(to settings: NSObject) {
        guard let value = UserDefaults.standard.object(forKey: "diag.refreshDeadline") as? Double else { return }
        guard settings.responds(to: NSSelectorFromString("setRefreshDeadline:")) else {
            debugLog("diag.refreshDeadline set but CGVirtualDisplaySettings has no refreshDeadline")
            return
        }
        settings.setValue(value, forKey: "refreshDeadline")
        debugLog("Virtual display refreshDeadline = \(value)")
    }
}

/// A borderless window on the virtual display with a bar that moves a fixed
/// step on every display-link tick, so every composited frame differs from
/// the last. Logs its tick rate once a second.
final class MotionPatternWindow {
    private var window: NSWindow?
    private var bar: CALayer?
    private var link: AnyObject?
    private var ticks = 0
    private var windowStart = CACurrentMediaTime()
    private var x: CGFloat = 0

    func show(on displayID: CGDirectDisplayID) {
        guard window == nil else { return }
        guard let screen = NSScreen.screens.first(where: {
            ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == displayID
        }) else {
            debugLog("Motion pattern: virtual display \(displayID) has no NSScreen yet")
            return
        }
        let size = NSSize(width: 800, height: 240)
        let frame = NSRect(x: screen.frame.midX - size.width / 2, y: screen.frame.midY - size.height / 2,
                           width: size.width, height: size.height)
        // Global coordinates: with `screen:` the rect would be read relative
        // to that screen and land off-screen (then the display link never fires).
        let w = NSWindow(contentRect: frame, styleMask: .borderless, backing: .buffered, defer: false)
        w.setFrame(frame, display: false)
        w.level = .floating
        w.isReleasedWhenClosed = false
        w.ignoresMouseEvents = true
        let view = NSView(frame: NSRect(origin: .zero, size: size))
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.black.cgColor
        let b = CALayer()
        b.backgroundColor = NSColor.white.cgColor
        b.frame = CGRect(x: 0, y: 0, width: 40, height: size.height)
        b.actions = ["position": NSNull(), "bounds": NSNull()]  // no implicit animation
        view.layer?.addSublayer(b)
        w.contentView = view
        w.orderFrontRegardless()
        window = w
        bar = b

        if #available(macOS 14.0, *) {
            let dl = view.displayLink(target: self, selector: #selector(tick(_:)))
            let preferred = UserDefaults.standard.integer(forKey: "diag.patternPreferredFps")
            if preferred > 0 {
                let rate = Float(preferred)
                dl.preferredFrameRateRange = CAFrameRateRange(minimum: rate, maximum: rate, preferred: rate)
                debugLog("Motion pattern display link preferredFrameRateRange = \(preferred)")
            }
            dl.add(to: .main, forMode: .common)
            link = dl
            debugLog("Motion pattern shown on display \(displayID) at \(NSStringFromRect(w.frame)), on screen \(w.screen.map { NSStringFromRect($0.frame) } ?? "none")")
        } else {
            debugLog("Motion pattern needs macOS 14 for a per-view display link")
        }
    }

    @available(macOS 14.0, *)
    @objc private func tick(_ link: CADisplayLink) {
        guard let bar, let width = window?.contentView?.bounds.width else { return }
        x += 8
        if x > width - bar.bounds.width { x = 0 }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        bar.frame.origin.x = x
        CATransaction.commit()

        ticks += 1
        let now = CACurrentMediaTime()
        if now - windowStart >= 1 {
            let hz = 1 / max(link.targetTimestamp - link.timestamp, 0.000_1)
            debugLog(String(format: "Motion pattern: %d display-link ticks/s (link interval %.1f Hz)", ticks, hz))
            ticks = 0
            windowStart = now
        }
    }

    func hide() {
        if #available(macOS 14.0, *) { (link as? CADisplayLink)?.invalidate() }
        link = nil
        window?.orderOut(nil)
        window = nil
        bar = nil
    }
}

/// Tells the power manager the user is active every 0.5 s, to test whether
/// WindowServer's 60 Hz compositing of the virtual display on an idle Mac
/// follows the HID idle state. diag.declareUserActivity = true.
final class UserActivityPinger {
    private var timer: DispatchSourceTimer?
    private var assertionID: IOPMAssertionID = 0

    static var enabled: Bool { UserDefaults.standard.bool(forKey: "diag.declareUserActivity") }

    func start() {
        stop()
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now(), repeating: 0.5)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            IOPMAssertionDeclareUserActivity("SharpScreen streaming" as CFString, kIOPMUserActiveLocal, &self.assertionID)
        }
        t.resume()
        timer = t
        debugLog("diag.declareUserActivity: declaring user activity every 0.5 s")
    }

    func stop() {
        timer?.cancel()
        timer = nil
        if assertionID != 0 { IOPMAssertionRelease(assertionID); assertionID = 0 }
    }
}
