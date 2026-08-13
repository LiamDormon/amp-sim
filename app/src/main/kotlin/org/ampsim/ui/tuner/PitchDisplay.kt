package org.ampsim.ui.tuner

import org.freedesktop.cairo.Context
import org.gnome.gdk.RGBA
import org.gnome.gobject.Value
import org.gnome.gtk.AccessibleProperty
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.DrawingArea
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.javagi.gobject.types.Types
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

private const val IDLE_PROMPT = "Play a note to begin"

/**
 * The Tuner tab's animated pitch readout: a bipolar segmented-LED arc with a
 * needle, styled like a VU-meter bridge mounted low in the frame — fuses
 * [org.ampsim.ui.chain.Dial]'s arc/pointer geometry with
 * [org.ampsim.ui.dashboard.VuMeter]'s segmented-ladder language, applied to
 * its most literal use case: amber sweeping to green as the detected pitch
 * homes in on the target.
 *
 * Contained in a single bounded "instrument panel" card (fixed CSS size, not
 * stretched to fill the tab) matching the recessed-panel language the
 * Dashboard already uses — this widget sets its own `halign`/`valign` to
 * `CENTER` so a caller can freely `vexpand`/`hexpand` it to claim layout
 * space without the panel itself stretching to fill that space.
 *
 * Segments light from the true center (0 cents) outward toward whichever side
 * the needle is on — a positional cue, not just a color one, so "how far and
 * which way" reads even without color vision. The needle position is eased
 * through a [BezierAnimatedValue], driven every frame by a `Widget.addTickCallback`
 * loop (started on demand, stopped once the tween settles) rather than only
 * being recomputed once per pitch-detection tick — a needle that only moves
 * when new data arrives looks like it's stepping, not tracking.
 */
class PitchDisplay : Box(Orientation.VERTICAL, 4) {

    private val noteLabel = Label("—").apply {
        addCssClass("tuner-note-name")
        halign = Align.CENTER
    }
    private val frequencyLabel = Label("").apply {
        addCssClass("tuner-frequency")
        halign = Align.CENTER
    }
    private val stage = DrawingArea().apply {
        addCssClass("tuner-stage")
        halign = Align.CENTER
        valign = Align.CENTER
    }
    private val statusLabel = Label(IDLE_PROMPT).apply {
        addCssClass("tuner-status")
        halign = Align.CENTER
    }
    private val detailLabel = Label("").apply {
        addCssClass("tuner-detail")
        halign = Align.CENTER
    }

    private val needleAngle = BezierAnimatedValue(initial = 0f)
    private var tickCallbackActive = false

    private var hasSignal = false
    private var inTune = false

    init {
        addCssClass("tuner-pitch-display")
        halign = Align.CENTER
        valign = Align.CENTER
        stage.setDrawFunc { _, cr, width, height -> draw(cr, width, height) }
        append(noteLabel)
        append(frequencyLabel)
        append(stage)
        append(statusLabel)
        append(detailLabel)
        updateAccessibleValue(IDLE_PROMPT)
    }

    /**
     * Feed a new detection result. Safe to call at whatever cadence the pitch
     * detector ticks at. [frequencyHz] is the raw detected pitch (not the
     * target's theoretical frequency), shown to confirm what's actually being
     * picked up.
     */
    fun update(hasSignal: Boolean, noteName: String?, frequencyHz: Float?, centsOff: Float?, inTune: Boolean) {
        this.hasSignal = hasSignal
        this.inTune = inTune

        val targetAngle = if (hasSignal && centsOff != null) {
            centsOff.coerceIn(-ARC_HALF_SWEEP_DEG.toFloat(), ARC_HALF_SWEEP_DEG.toFloat())
        } else {
            0f
        }
        if (hasSignal) {
            needleAngle.update(targetAngle)
            ensureAnimating()
        } else {
            needleAngle.snapTo(0f)
        }

        val accessibleText: String
        statusLabel.removeCssClass("tuner-status--in-tune")
        statusLabel.removeCssClass("tuner-status--adjust")
        if (!hasSignal || noteName == null || centsOff == null) {
            noteLabel.text = "—"
            frequencyLabel.text = ""
            statusLabel.text = IDLE_PROMPT
            detailLabel.text = ""
            accessibleText = IDLE_PROMPT
        } else {
            noteLabel.text = noteName
            frequencyLabel.text = if (frequencyHz != null) "%.1f Hz".format(frequencyHz) else ""
            if (inTune) {
                statusLabel.text = "In tune"
                statusLabel.addCssClass("tuner-status--in-tune")
                detailLabel.text = "Perfectly in tune"
                accessibleText = "$noteName, in tune"
            } else {
                val rounded = round(centsOff).toInt()
                statusLabel.addCssClass("tuner-status--adjust")
                if (rounded > 0) {
                    statusLabel.text = "▼ Tune down"
                    detailLabel.text = "$rounded cents sharp"
                    accessibleText = "$noteName, $rounded cents sharp. Tune down."
                } else {
                    statusLabel.text = "▲ Tune up"
                    detailLabel.text = "${-rounded} cents flat"
                    accessibleText = "$noteName, ${-rounded} cents flat. Tune up."
                }
            }
        }

        updateAccessibleValue(accessibleText)
        stage.queueDraw()
    }

    /**
     * Start a per-frame `Widget.addTickCallback` loop advancing [needleAngle]
     * and redrawing, if one isn't already running. Returning `false` from
     * the callback tells GTK to remove it automatically once the tween
     * settles, so this costs nothing while the needle is at rest.
     */
    private fun ensureAnimating() {
        if (tickCallbackActive) return
        tickCallbackActive = true
        stage.addTickCallback { _, _ ->
            needleAngle.advance()
            stage.queueDraw()
            val stillAnimating = needleAngle.isAnimating
            if (!stillAnimating) tickCallbackActive = false
            stillAnimating
        }
    }

    private fun updateAccessibleValue(text: String) {
        stage.updateProperty(
            arrayOf(AccessibleProperty.VALUE_TEXT),
            arrayOf(Value().apply { init(Types.STRING); setString(text) })
        )
    }

    private fun themeColorOr(name: String, fallback: Triple<Double, Double, Double>): Triple<Double, Double, Double> {
        val rgba = RGBA()
        return if (stage.styleContext.lookupColor(name, rgba)) {
            Triple(rgba.readRed().toDouble(), rgba.readGreen().toDouble(), rgba.readBlue().toDouble())
        } else {
            fallback
        }
    }

    private fun draw(cr: Context, width: Int, height: Int) {
        val cx = width / 2.0
        val cy = height * (1.0 - PIVOT_MARGIN_FRACTION)
        val radius = min(
            height * ARC_HEIGHT_FRACTION,
            (width / 2.0) / sin(Math.toRadians(ARC_HALF_SWEEP_DEG))
        )
        val trackWidth = radius * TRACK_WIDTH_FRACTION

        val green = themeColorOr("led_green", Triple(0.24, 0.86, 0.45))
        val amber = themeColorOr("amp_accent", Triple(0.91, 0.64, 0.32))

        val needleClockDeg = needleAngle.current
        val degreesPerSegment = 2.0 * ARC_HALF_SWEEP_DEG / SEGMENT_COUNT

        // 1. Unlit ladder + 2. lit span (center outward toward the needle's side).
        for (i in 0 until SEGMENT_COUNT) {
            val segCenterDeg = -ARC_HALF_SWEEP_DEG + (i + 0.5) * degreesPerSegment
            val lit = hasSignal && isLit(segCenterDeg, needleClockDeg)
            val startDeg = segCenterDeg - degreesPerSegment / 2.0 + SEGMENT_GAP_DEG / 2.0
            val endDeg = segCenterDeg + degreesPerSegment / 2.0 - SEGMENT_GAP_DEG / 2.0

            cr.setLineWidth(trackWidth)
            if (lit) {
                val (r, g, b) = lerpColor(green, amber, colorFraction(segCenterDeg))
                cr.setSourceRGB(r, g, b)
            } else {
                cr.setSourceRGBA(1.0, 1.0, 1.0, 0.06)
            }
            cr.arc(cx, cy, radius, clockToCairoRad(startDeg), clockToCairoRad(endDeg))
            cr.stroke()
        }

        // 3. Needle.
        val needleLength = radius * NEEDLE_LENGTH_FRACTION
        val needleRad = clockToCairoRad(needleClockDeg.toDouble())
        val px = cx + needleLength * cos(needleRad)
        val py = cy + needleLength * sin(needleRad)
        val (needleR, needleG, needleB) = if (hasSignal) {
            lerpColor(green, amber, colorFraction(needleClockDeg))
        } else {
            Triple(1.0, 1.0, 1.0)
        }
        cr.setSourceRGBA(needleR, needleG, needleB, if (hasSignal) 1.0 else 0.25)
        cr.setLineWidth(radius * NEEDLE_WIDTH_FRACTION)
        cr.moveTo(cx, cy)
        cr.lineTo(px, py)
        cr.stroke()

        // 4. Bullseye — a crisp, solid LED dot (lit green when locked in tune,
        // dark recessed otherwise), not a soft glow: a real indicator LED
        // has a sharp edge, and a blurred radial bloom read as fuzzy/amateurish
        // rather than precise.
        val bullseyeRadius = radius * BULLSEYE_RADIUS_FRACTION
        if (hasSignal && inTune) {
            cr.setSourceRGB(green.first, green.second, green.third)
        } else {
            cr.setSourceRGBA(0.0, 0.0, 0.0, 0.45)
        }
        cr.arc(cx, cy, bullseyeRadius, 0.0, 2 * PI)
        cr.fill()

        // Thin dark bezel ring, always drawn, so the dot reads as a mounted
        // indicator light rather than a flat painted circle.
        cr.setSourceRGBA(0.0, 0.0, 0.0, 0.35)
        cr.setLineWidth(bullseyeRadius * 0.22)
        cr.arc(cx, cy, bullseyeRadius, 0.0, 2 * PI)
        cr.stroke()
    }

    /** True if [segCenterDeg] lies between true-center (0) and [needleClockDeg], inclusive. */
    private fun isLit(segCenterDeg: Double, needleClockDeg: Float): Boolean = when {
        needleClockDeg >= 0f -> segCenterDeg >= -LIT_EPSILON && segCenterDeg <= needleClockDeg + LIT_EPSILON
        else -> segCenterDeg <= LIT_EPSILON && segCenterDeg >= needleClockDeg - LIT_EPSILON
    }

    private fun colorFraction(deg: Float): Float = (abs(deg) / AMBER_AT_CENTS).coerceIn(0f, 1f)
    private fun colorFraction(deg: Double): Float = colorFraction(deg.toFloat())

    private fun lerpColor(from: Triple<Double, Double, Double>, to: Triple<Double, Double, Double>, t: Float): Triple<Double, Double, Double> =
        Triple(
            from.first + (to.first - from.first) * t,
            from.second + (to.second - from.second) * t,
            from.third + (to.third - from.third) * t
        )

    /** Convert a "clock angle" (0 = 12 o'clock, clockwise-positive) to cairo's arc-angle convention. */
    private fun clockToCairoRad(clockDeg: Double): Double = Math.toRadians(clockDeg - 90.0)

    // ── Test hooks ─────────────────────────────────────────────────────────

    internal fun noteText(): String = noteLabel.text
    internal fun frequencyText(): String = frequencyLabel.text
    internal fun statusText(): String = statusLabel.text
    internal fun detailText(): String = detailLabel.text
    internal fun needleAngleForTest(): Float = needleAngle.current

    companion object {
        private const val SEGMENT_COUNT = 21
        private const val ARC_HALF_SWEEP_DEG = 50.0
        // Large enough that the bullseye's bloom (BULLSEYE_RADIUS_FRACTION *
        // radius * 3.2, drawn centered on the pivot) doesn't clip against the
        // bottom of the stage.
        private const val PIVOT_MARGIN_FRACTION = 0.12
        private const val ARC_HEIGHT_FRACTION = 0.82
        private const val SEGMENT_GAP_DEG = 1.4
        private const val TRACK_WIDTH_FRACTION = 0.09
        private const val NEEDLE_LENGTH_FRACTION = 0.86
        private const val NEEDLE_WIDTH_FRACTION = 0.02
        private const val BULLSEYE_RADIUS_FRACTION = 0.05
        private const val AMBER_AT_CENTS = 20f
        private const val LIT_EPSILON = 0.05
    }
}
