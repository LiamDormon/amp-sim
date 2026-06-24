package org.example.ui

import java.lang.foreign.MemorySegment
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.ViewSwitcherSidebar
import org.javagi.gtk.annotations.GtkChild
import org.javagi.gtk.annotations.GtkTemplate

@GtkTemplate(name="AppWindow", ui = "/org/example/ampsim/mainwindow.ui")
class AppWindow : ApplicationWindow {
    constructor() : super()

    constructor(address: MemorySegment) : super(address)

    @GtkChild(name = "view_sidebar")
    @JvmField
    var viewSidebar: ViewSwitcherSidebar? = null
}
