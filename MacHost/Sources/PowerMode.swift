import Foundation
import os

/// How hard the tablet works on a still screen (issue #16).
///
/// Power saving (default): once nothing new has come for a moment, the Mac
/// repeats the last frame 10 times a second instead of at the display rate,
/// and the tablet lets its panel leave the top refresh rate until something
/// moves again. Performance: repeats at the display rate and the top refresh
/// rate all the time, as before.
enum PowerMode: String, CaseIterable {
    case powerSaving
    case performance

    /// Wire value of the host -> client power mode message.
    var wireValue: UInt8 { self == .powerSaving ? 1 : 0 }

    private static let current = OSAllocatedUnfairLock(initialState: PowerMode.powerSaving)

    /// The mode streaming uses now; any thread.
    static var active: PowerMode {
        get { current.withLock { $0 } }
        set { current.withLock { $0 = newValue } }
    }
}
