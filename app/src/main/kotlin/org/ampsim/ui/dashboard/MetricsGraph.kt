package org.ampsim.ui.dashboard

import org.ampsim.metrics.MetricsHistoryBuffer
import org.freedesktop.cairo.Context
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.DrawingArea
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation

/**
 * A small history line-graph for one aggregate metric (CPU%, chain latency,
 * heap usage) — custom-drawn like [org.ampsim.ui.chain.Dial]/[VuMeter] rather
 * than a charting library, since none exists in this project.
 *
 * Samples pushed via [pushSample] are expected to already be smoothed by the
 * caller (see `org.ampsim.metrics.MetricsSmoother`) — this widget does not
 * re-smooth internally, so every consumer of a given metric stream (this
 * graph, the CSV log) reads the same de-jittered number rather than each
 * smoothing independently and disagreeing with each other.
 */
class MetricsGraph(
    label: String,
    private val historyCapacity: Int = DEFAULT_HISTORY_CAPACITY
) : Box(Orientation.VERTICAL, 4) {

    private val history = MetricsHistoryBuffer<Float>(historyCapacity)
    private val canvas = DrawingArea()
    private val labelWidget = Label(label)
    private val valueLabel = Label("")

    init {
        addCssClass("dashboard-metrics-graph")

        canvas.addCssClass("dashboard-metrics-graph-canvas")
        canvas.hexpand = true
        canvas.setDrawFunc { _, cr, width, height -> draw(cr, width, height) }

        labelWidget.addCssClass("amp-legend")
        labelWidget.halign = Align.START
        labelWidget.hexpand = true

        valueLabel.addCssClass("dashboard-metrics-graph-value")

        val header = Box(Orientation.HORIZONTAL, 6)
        header.append(labelWidget)
        header.append(valueLabel)

        append(header)
        append(canvas)
    }

    /**
     * Push a new sample. [normalized] (`[0, 1]`, clamped) is what gets
     * plotted — the graph's own fixed vertical scale — while [displayText]
     * is the free-form label text (e.g. `"42%"`, `"3.2 ms"`), since the
     * plotted fraction and the human-readable value are rarely the same
     * number (a latency graph plots `ms / someCeiling`, not raw ms).
     */
    fun pushSample(normalized: Float, displayText: String) {
        history.push(normalized.coerceIn(0f, 1f))
        valueLabel.text = displayText
        canvas.queueDraw()
    }

    private fun draw(cr: Context, width: Int, height: Int) {
        val samples = history.snapshot()
        if (samples.isEmpty()) return

        val w = width.toDouble()
        val h = height.toDouble()
        val stepX = w / (historyCapacity - 1).coerceAtLeast(1)
        // Right-align the trailing edge of history against the widget's
        // right edge, so a partially-filled history reads as "building up"
        // from the left rather than stretching to fill the width.
        val startIndex = historyCapacity - samples.size

        // Backing plate, matching VuMeter's unlit-segment tone.
        cr.setSourceRGBA(1.0, 1.0, 1.0, 0.06)
        cr.moveTo(0.0, 0.0)
        cr.lineTo(w, 0.0)
        cr.lineTo(w, h)
        cr.lineTo(0.0, h)
        cr.closePath()
        cr.fill()

        if (samples.size < 2) return

        // Filled area under the line.
        cr.moveTo(startIndex * stepX, h)
        for (i in samples.indices) {
            cr.lineTo((startIndex + i) * stepX, h - samples[i] * h)
        }
        cr.lineTo((startIndex + samples.size - 1) * stepX, h)
        cr.closePath()
        cr.setSourceRGBA(ACCENT_R, ACCENT_G, ACCENT_B, 0.18)
        cr.fill()

        // Line on top.
        cr.moveTo(startIndex * stepX, h - samples[0] * h)
        for (i in 1 until samples.size) {
            cr.lineTo((startIndex + i) * stepX, h - samples[i] * h)
        }
        cr.setSourceRGBA(ACCENT_R, ACCENT_G, ACCENT_B, 0.9)
        cr.setLineWidth(1.5)
        cr.stroke()
    }

    // ── Test hooks ─────────────────────────────────────────────────────────

    internal fun currentSamplesForTest(): List<Float> = history.snapshot()
    internal fun valueText(): String = valueLabel.text
    internal fun labelText(): String = labelWidget.text

    companion object {
        private const val DEFAULT_HISTORY_CAPACITY = 150
        private const val ACCENT_R = 0.35
        private const val ACCENT_G = 0.78
        private const val ACCENT_B = 0.98
    }
}
