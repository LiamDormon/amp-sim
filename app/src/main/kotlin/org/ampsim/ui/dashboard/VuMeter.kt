package org.ampsim.ui.dashboard

import kotlin.math.roundToInt
import org.freedesktop.cairo.Context
import org.freedesktop.cairo.RadialGradient
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.DrawingArea
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation

/** Which axis a [VuMeter]'s segment ladder climbs. */
enum class MeterOrientation { VERTICAL, HORIZONTAL }

/**
 * A segmented LED-ladder level meter, custom-drawn like [org.ampsim.ui.chain.Dial]
 * rather than a plain progress bar — the same instrument language real rack gear
 * uses for level metering, and legible at a glance the way a continuous bar isn't.
 * Reused three times on the Dashboard (input, output, CPU) so the whole panel
 * reads as one instrument rather than three different widget styles.
 *
 * Raw levels fed via [setLevel] are smoothed through an [AnimatedValue] so the
 * ladder doesn't visibly step between the driving timer's ~20Hz samples.
 */
class VuMeter(
    private val label: String,
    private val orientation: MeterOrientation = MeterOrientation.VERTICAL,
    private val segmentCount: Int = DEFAULT_SEGMENT_COUNT
) : Box(if (orientation == MeterOrientation.VERTICAL) Orientation.VERTICAL else Orientation.HORIZONTAL, 6) {

    private val ladder = DrawingArea()
    private val labelWidget = Label(label)
    private val animated = AnimatedValue()

    init {
        addCssClass("dashboard-vu-meter")
        addCssClass(if (orientation == MeterOrientation.VERTICAL) "dashboard-vu-meter--vertical" else "dashboard-vu-meter--horizontal")

        ladder.addCssClass("dashboard-vu-meter-ladder")
        ladder.setDrawFunc { _, cr, width, height -> draw(cr, width, height) }

        labelWidget.addCssClass("dashboard-vu-meter-label")

        if (orientation == MeterOrientation.VERTICAL) {
            halign = Align.CENTER
            append(ladder)
            append(labelWidget)
        } else {
            // Fills whatever width its container gives it, rather than sitting
            // pinned at its CSS min-width with empty space beside it — the
            // ladder's own min-width is only a floor for narrow windows.
            hexpand = true
            ladder.hexpand = true
            valign = Align.CENTER
            append(labelWidget)
            append(ladder)
        }
    }

    /** Feed a new raw level in `[0, 1]`; out-of-range input is clamped. Smoothed internally. */
    fun setLevel(rawLevel: Float) {
        animated.update(rawLevel.coerceIn(0f, 1f))
        ladder.queueDraw()
    }

    private fun draw(cr: Context, width: Int, height: Int) {
        val litCount = (animated.current * segmentCount).roundToInt().coerceIn(0, segmentCount)
        val isVertical = orientation == MeterOrientation.VERTICAL
        val trackLength = (if (isVertical) height else width).toDouble()
        val breadth = (if (isVertical) width else height).toDouble()
        val segmentLength = (trackLength - (segmentCount - 1) * SEGMENT_GAP) / segmentCount

        for (i in 0 until segmentCount) {
            // Segment 0 is the start of the ladder (bottom when vertical, left when
            // horizontal); index climbs toward the "hot" end either way.
            val segmentStart = i * (segmentLength + SEGMENT_GAP)
            val lit = i < litCount
            val zoneFraction = (i + 1).toFloat() / segmentCount
            drawSegment(cr, isVertical, segmentStart, segmentLength, breadth, trackLength, lit, zoneFraction)
        }
    }

    private fun drawSegment(
        cr: Context,
        isVertical: Boolean,
        segmentStart: Double,
        segmentLength: Double,
        breadth: Double,
        trackLength: Double,
        lit: Boolean,
        zoneFraction: Float
    ) {
        // Index climbs from the "cold" end of the ladder; for a vertical meter
        // that's the bottom, so segment 0's rect is drawn at the bottom edge.
        val x: Double
        val y: Double
        val w: Double
        val h: Double
        if (isVertical) {
            x = 0.0
            y = trackLength - segmentStart - segmentLength
            w = breadth
            h = segmentLength
        } else {
            x = segmentStart
            y = 0.0
            w = segmentLength
            h = breadth
        }

        if (!lit) {
            cr.setSourceRGBA(1.0, 1.0, 1.0, 0.06)
            roundedRect(cr, x, y, w, h, SEGMENT_RADIUS)
            cr.fill()
            return
        }

        val (r, g, b) = zoneColor(zoneFraction)
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        val glossRadius = maxOf(w, h)
        val gloss = RadialGradient.create(cx, y + h * 0.15, 0.0, cx, cy, glossRadius)
        gloss.addColorStopRGB(0.0, minOf(r + 0.35, 1.0), minOf(g + 0.35, 1.0), minOf(b + 0.35, 1.0))
        gloss.addColorStopRGB(1.0, r, g, b)
        cr.setSource(gloss)
        roundedRect(cr, x, y, w, h, SEGMENT_RADIUS)
        cr.fill()
    }

    private fun zoneColor(zoneFraction: Float): Triple<Double, Double, Double> = when {
        zoneFraction <= GREEN_ZONE_FRACTION -> Triple(0.24, 0.86, 0.45)
        zoneFraction <= AMBER_ZONE_FRACTION -> Triple(0.91, 0.64, 0.32)
        else -> Triple(0.95, 0.33, 0.35)
    }

    private fun roundedRect(cr: Context, x: Double, y: Double, w: Double, h: Double, radius: Double) {
        val r = radius.coerceAtMost(minOf(w, h) / 2.0)
        cr.newSubPath()
        cr.arc(x + w - r, y + r, r, -Math.PI / 2, 0.0)
        cr.arc(x + w - r, y + h - r, r, 0.0, Math.PI / 2)
        cr.arc(x + r, y + h - r, r, Math.PI / 2, Math.PI)
        cr.arc(x + r, y + r, r, Math.PI, 3 * Math.PI / 2)
        cr.closePath()
    }

    // ── Test hooks ──────────────────────────────────────────────────────────

    internal fun currentLevel(): Float = animated.current
    internal fun labelText(): String = labelWidget.text

    companion object {
        private const val DEFAULT_SEGMENT_COUNT = 12
        private const val SEGMENT_GAP = 2.0
        private const val SEGMENT_RADIUS = 1.5
        private const val GREEN_ZONE_FRACTION = 0.7f
        private const val AMBER_ZONE_FRACTION = 0.9f
    }
}
