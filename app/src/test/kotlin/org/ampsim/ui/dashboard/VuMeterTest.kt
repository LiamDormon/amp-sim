package org.ampsim.ui.dashboard

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.gnome.gtk.Gtk

class VuMeterTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    @Test
    fun labelTextReflectsConstructorArgument() {
        val meter = VuMeter("IN")
        assertEquals("IN", meter.labelText())
    }

    @Test
    fun setLevelUpdatesCurrentLevel() {
        val meter = VuMeter("OUT")
        meter.setLevel(0.8f)
        // First sample has no prior timestamp to smooth from, so it snaps.
        assertEquals(0.8f, meter.currentLevel())
    }

    @Test
    fun setLevelClampsAboveOne() {
        val meter = VuMeter("IN")
        meter.setLevel(1.5f)
        assertEquals(1f, meter.currentLevel())
    }

    @Test
    fun setLevelClampsBelowZero() {
        val meter = VuMeter("IN")
        meter.setLevel(-0.2f)
        assertEquals(0f, meter.currentLevel())
    }
}
