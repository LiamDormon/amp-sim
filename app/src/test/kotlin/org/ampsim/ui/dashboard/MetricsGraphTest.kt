package org.ampsim.ui.dashboard

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.gnome.gtk.Gtk

class MetricsGraphTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    @Test
    fun labelTextReflectsConstructorArgument() {
        val graph = MetricsGraph("CPU")
        assertEquals("CPU", graph.labelText())
    }

    @Test
    fun pushSampleAppendsToHistoryAndUpdatesTheValueLabel() {
        val graph = MetricsGraph("CPU")

        graph.pushSample(0.5f, "50%")

        assertEquals(listOf(0.5f), graph.currentSamplesForTest())
        assertEquals("50%", graph.valueText())
    }

    @Test
    fun pushSampleClampsAboveOne() {
        val graph = MetricsGraph("CPU")

        graph.pushSample(1.5f, "150%")

        assertEquals(listOf(1f), graph.currentSamplesForTest())
    }

    @Test
    fun pushSampleClampsBelowZero() {
        val graph = MetricsGraph("CPU")

        graph.pushSample(-0.2f, "-20%")

        assertEquals(listOf(0f), graph.currentSamplesForTest())
    }

    @Test
    fun historyEvictsTheOldestSampleOnceCapacityIsExceeded() {
        val graph = MetricsGraph("CPU", historyCapacity = 3)

        for (i in 1..4) graph.pushSample(i / 10f, "$i")

        assertEquals(listOf(0.2f, 0.3f, 0.4f), graph.currentSamplesForTest())
    }
}
