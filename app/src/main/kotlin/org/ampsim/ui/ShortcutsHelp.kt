package org.ampsim.ui

import org.gnome.gtk.ShortcutType
import org.gnome.gtk.ShortcutsGroup
import org.gnome.gtk.ShortcutsSection
import org.gnome.gtk.ShortcutsShortcut
import org.gnome.gtk.ShortcutsWindow

/**
 * Build the "Keyboard Shortcuts" window, listing every "win.*" accelerator
 * registered in [registerWindowActions]. `AppWindow.setHelpOverlay(...)` (see
 * `App.kt`'s onActivate) makes GTK auto-wire the already-existing
 * `win.show-help-overlay` menu action to present this — no manual action
 * registration needed for it.
 */
fun buildShortcutsWindow(): ShortcutsWindow {
    fun shortcut(title: String, accel: String): ShortcutsShortcut =
        ShortcutsShortcut.builder()
            .setTitle(title)
            .setAccelerator(accel)
            .setShortcutType(ShortcutType.ACCELERATOR)
            .build()

    val navigation = ShortcutsGroup.builder().setTitle("Navigation").build().apply {
        addShortcut(shortcut("Dashboard", "<Alt>1"))
        addShortcut(shortcut("Chain Editor", "<Alt>2"))
        addShortcut(shortcut("Presets", "<Alt>3"))
        addShortcut(shortcut("Settings", "<Alt>4"))
        addShortcut(shortcut("Toggle Library", "<Primary>b"))
    }
    val actions = ShortcutsGroup.builder().setTitle("Actions").build().apply {
        addShortcut(shortcut("Save Preset", "<Primary>s"))
        addShortcut(shortcut("Load Preset", "<Primary>l"))
        addShortcut(shortcut("New Chain", "<Primary>n"))
        addShortcut(shortcut("Undo", "<Primary>z"))
    }
    val chainEditor = ShortcutsGroup.builder().setTitle("Chain Editor").build().apply {
        // Tab to a unit's dial first (e.g. drive/tone/level under its
        // Parameters drawer, or the noise-gate threshold), then use these to
        // adjust it — same step size as the scroll wheel.
        addShortcut(shortcut("Adjust Focused Dial", "Up Down Left Right"))
        addShortcut(shortcut("Open Unit Context Menu", "Menu <Shift>F10"))
    }
    val section = ShortcutsSection.builder().setSectionName("main").build().apply {
        addGroup(navigation)
        addGroup(actions)
        addGroup(chainEditor)
    }

    return ShortcutsWindow().apply { addSection(section) }
}
