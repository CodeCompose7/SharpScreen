import Foundation
import AppKit

print("🚀 SharpScreen starting...")

// No args needed

// Entry point
let app = NSApplication.shared

// Setup main menu for keyboard shortcuts (Command+Q, etc.)
let mainMenu = NSMenu()

// App menu
let appMenu = NSMenu()
let appMenuItem = NSMenuItem()
appMenuItem.submenu = appMenu
appMenu.addItem(NSMenuItem(title: String(localized: "About SharpScreen"), action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: ""))
let checkForUpdatesItem = NSMenuItem(title: String(localized: "Check for Updates…"), action: #selector(AppUpdater.checkForUpdates(_:)), keyEquivalent: "")
checkForUpdatesItem.target = AppUpdater.shared
appMenu.addItem(checkForUpdatesItem)
appMenu.addItem(NSMenuItem.separator())
appMenu.addItem(NSMenuItem(title: String(localized: "Quit SharpScreen"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
mainMenu.addItem(appMenuItem)

// Edit menu (for standard text editing shortcuts)
let editMenu = NSMenu(title: String(localized: "Edit"))
let editMenuItem = NSMenuItem()
editMenuItem.submenu = editMenu
editMenu.addItem(NSMenuItem(title: String(localized: "Cut"), action: #selector(NSText.cut(_:)), keyEquivalent: "x"))
editMenu.addItem(NSMenuItem(title: String(localized: "Copy"), action: #selector(NSText.copy(_:)), keyEquivalent: "c"))
editMenu.addItem(NSMenuItem(title: String(localized: "Paste"), action: #selector(NSText.paste(_:)), keyEquivalent: "v"))
editMenu.addItem(NSMenuItem(title: String(localized: "Select All"), action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"))
mainMenu.addItem(editMenuItem)

app.mainMenu = mainMenu

let delegate = AppDelegate()

app.setActivationPolicy(.regular)

app.delegate = delegate
app.run()
