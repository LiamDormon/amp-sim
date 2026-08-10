package org.ampsim.ui

import java.nio.file.Paths
import org.gnome.gio.Resource
import org.gnome.gtk.Gtk
import org.javagi.gtk.types.TemplateTypes

/**
 * One-time GTK bootstrap for tests that construct a real [AppWindow] (a
 * `@GtkTemplate` class). [TemplateTypes.register] performs process-wide
 * GObject type registration — calling it more than once (e.g. once per test
 * class, each guarding with its own private flag) corrupts the registered
 * GType and leaves `@GtkChild` fields null on subsequently constructed
 * windows. Every test class that constructs `AppWindow()` must funnel
 * through this single shared guard instead of rolling its own.
 */
object AppWindowTestSupport {
    private var registered = false

    fun ensureAppWindowIsRegistered() {
        if (registered) return
        Gtk.init()
        val resourceUrl = checkNotNull(javaClass.getResource("/ampsim.gresource")) {
            "Missing ampsim.gresource on the test classpath"
        }
        val resource = Resource.load(Paths.get(resourceUrl.toURI()).toString())
        resource.resourcesRegister()
        TemplateTypes.register(AppWindow::class.java)
        registered = true
    }
}
