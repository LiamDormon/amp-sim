package org.ampsim.ui.chain

import org.freedesktop.cairo.Context
import org.freedesktop.cairo.RadialGradient
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.DrawingArea
import org.gnome.gtk.EventControllerScroll
import org.gnome.gtk.EventControllerScrollFlags
import org.gnome.gtk.GestureDrag
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A rotary knob control for a single float parameter, styled after a pedal's
 * physical dial. Value changes come from a vertical drag (up increases, down
 * decreases) or the scroll wheel, matching how software knobs are normally
 * driven with a mouse rather than trying to track click angle directly.
 */
class Dial(
    val min: Float,
    val max: Float,
    initialValue: Float,
    private val unitLabel: String = "",
    private val decimals: Int = 2,
    private val onChanged: (Float) -> Unit = {}
) : Box(Orientation.VERTICAL, 2) {

    private val knob = DrawingArea()
    private val valueLabel = Label("")

    var value: Float = initialValue.coerceIn(min, max)
        private set

    private var dragStartValue = value

    init {
        addCssClass("chain-dial")
        halign = Align.CENTER

        // No fixed content size here: the knob's size comes entirely from the
        // "chain-dial-knob" CSS class (in em, so it scales with the user's text
        // size / GNOME accessibility zoom). draw() reads the actual allocated
        // width/height on every call, so the rendering always matches.
        knob.addCssClass("chain-dial-knob")
        knob.setDrawFunc { _, cr, width, height -> draw(cr, width, height) }

        valueLabel.addCssClass("chain-dial-value")

        val dragGesture = GestureDrag()
        dragGesture.onDragBegin { _, _ -> dragStartValue = value }
        dragGesture.onDragUpdate { _, offsetY -> applyDrag(offsetY) }
        knob.addController(dragGesture)

        val scrollController = EventControllerScroll(EventControllerScrollFlags.VERTICAL)
        scrollController.onScroll { _, deltaY -> applyScroll(deltaY); true }
        knob.addController(scrollController)

        append(knob)
        append(valueLabel)

        updateLabel()
    }

    private fun scrollStep(): Float = (max - min) * SCROLL_STEP_FRACTION

    /** Recompute [value] from a single scroll-wheel step (positive = scroll down/decrease). */
    internal fun applyScroll(deltaY: Double) {
        setValue(value - deltaY.toFloat() * scrollStep())
    }

    /**
     * Recompute [value] from a cumulative vertical drag offset (GTK's drag-update
     * offset). Sensitivity is derived from the knob's actual on-screen size
     * (a full top-to-bottom drag across [DRAG_HEIGHT_MULTIPLES] knob-heights
     * covers the whole range) rather than a fixed pixel count, so a knob
     * rendered larger under GNOME's text-scaling setting stays equally easy
     * to control instead of feeling twitchier.
     */
    internal fun applyDrag(offsetY: Double) {
        val range = max - min
        val dragDistanceForFullRange = knob.height.takeIf { it > 0 }?.let { it * DRAG_HEIGHT_MULTIPLES }
            ?: FALLBACK_DRAG_PIXELS_FOR_FULL_RANGE
        val delta = (-offsetY / dragDistanceForFullRange).toFloat() * range
        setValue(dragStartValue + delta)
    }

    /** Set the value, clamping to range and notifying [onChanged] if it actually changed. */
    fun setValue(newValue: Float) {
        val clamped = newValue.coerceIn(min, max)
        if (clamped == value) return
        value = clamped
        updateLabel()
        knob.queueDraw()
        onChanged(value)
    }

    /** Sync the displayed value without notifying [onChanged] (e.g. an external chain reload). */
    fun setValueSilently(newValue: Float) {
        val clamped = newValue.coerceIn(min, max)
        if (clamped == value) return
        value = clamped
        updateLabel()
        knob.queueDraw()
    }

    private fun updateLabel() {
        val formatted = "%.${decimals}f".format(value)
        valueLabel.text = if (unitLabel.isNotEmpty()) "$formatted $unitLabel" else formatted
    }

    /**
     * Every measurement here is derived from [width]/[height] (the widget's
     * actual allocated size) rather than a fixed pixel constant, so the knob
     * renders proportionally correctly whatever size the "chain-dial-knob"
     * CSS class ends up giving it.
     */
    private fun draw(cr: Context, width: Int, height: Int) {
        val cx = width / 2.0
        val cy = height / 2.0
        val radius = minOf(width, height) / 2.0 * KNOB_FILL_FRACTION
        val trackWidth = radius * TRACK_WIDTH_FRACTION
        val trackRadius = radius - trackWidth
        val bezelWidth = radius * BEZEL_WIDTH_FRACTION

        val t = if (max > min) ((value - min) / (max - min)).coerceIn(0f, 1f) else 0f
        val valueClockDeg = START_ANGLE_DEG + t * SWEEP_DEGREES

        // Knob body: a radial gradient lit from the upper-left gives the cap a
        // convex, physical-plastic look instead of a flat filled disc.
        val body = RadialGradient.create(
            cx - radius * 0.35, cy - radius * 0.35, radius * 0.05,
            cx, cy, radius
        )
        body.addColorStopRGB(0.0, 0.38, 0.38, 0.40)
        body.addColorStopRGB(1.0, 0.15, 0.15, 0.17)
        cr.setSource(body)
        cr.arc(cx, cy, radius, 0.0, 2 * PI)
        cr.fill()

        cr.setSourceRGB(0.04, 0.04, 0.05)
        cr.setLineWidth(bezelWidth)
        cr.arc(cx, cy, radius, 0.0, 2 * PI)
        cr.stroke()

        // Background track, full sweep.
        cr.setSourceRGBA(1.0, 1.0, 1.0, 0.12)
        cr.setLineWidth(trackWidth)
        cr.arc(cx, cy, trackRadius, clockToCairoRad(START_ANGLE_DEG), clockToCairoRad(START_ANGLE_DEG + SWEEP_DEGREES))
        cr.stroke()

        // Filled arc from the start up to the current value, in the app's
        // single accent color (warm amber = "this is live/active").
        cr.setSourceRGB(0.85, 0.54, 0.24)
        cr.setLineWidth(trackWidth)
        cr.arc(cx, cy, trackRadius, clockToCairoRad(START_ANGLE_DEG), clockToCairoRad(valueClockDeg))
        cr.stroke()

        // Pointer, capped with a small index dot.
        val angleRad = Math.toRadians(valueClockDeg)
        val pointerLength = radius * POINTER_LENGTH_FRACTION
        val px = cx + pointerLength * sin(angleRad)
        val py = cy - pointerLength * cos(angleRad)
        cr.setSourceRGB(0.95, 0.96, 0.98)
        cr.setLineWidth(radius * POINTER_WIDTH_FRACTION)
        cr.moveTo(cx, cy)
        cr.lineTo(px, py)
        cr.stroke()
        cr.arc(px, py, radius * POINTER_DOT_FRACTION, 0.0, 2 * PI)
        cr.fill()
    }

    /** Convert a "clock angle" (0 = 12 o'clock, clockwise-positive) to cairo's arc-angle convention. */
    private fun clockToCairoRad(clockDeg: Double): Double = Math.toRadians(clockDeg - 90.0)

    companion object {
        private const val SCROLL_STEP_FRACTION = 0.02f
        private const val START_ANGLE_DEG = -135.0
        private const val SWEEP_DEGREES = 270.0

        // A drag spanning this many knob-heights covers the full parameter range.
        private const val DRAG_HEIGHT_MULTIPLES = 3.2
        // Used only before the knob's first size allocation (height still 0).
        private const val FALLBACK_DRAG_PIXELS_FOR_FULL_RANGE = 140.0

        // Proportions of the knob's own radius — every stroke/gap scales with
        // whatever size CSS gives the widget instead of a fixed pixel count.
        private const val KNOB_FILL_FRACTION = 0.88
        private const val BEZEL_WIDTH_FRACTION = 0.07
        private const val TRACK_WIDTH_FRACTION = 0.16
        private const val POINTER_LENGTH_FRACTION = 0.68
        private const val POINTER_WIDTH_FRACTION = 0.09
        private const val POINTER_DOT_FRACTION = 0.07
    }
}
