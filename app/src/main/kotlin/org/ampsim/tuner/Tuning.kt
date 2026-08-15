package org.ampsim.tuner

/**
 * One string of a [Tuning]. [stringNumber] follows real guitar convention
 * (6 = lowest/thickest, 1 = highest/thinnest) so a future 7-string or bass
 * tuning has an unambiguous field to extend without reordering. [positionalLabel]
 * ("6th String") stays stable across tuning changes; [note] is what changes
 * when a future alternate tuning swaps in.
 */
data class TuningString(val stringNumber: Int, val positionalLabel: String, val note: Note)

/** A named, ordered (low to high) set of target pitches for the tuner's manual mode. */
data class Tuning(val id: String, val displayName: String, val strings: List<TuningString>)

/** Built-in tunings. Currently standard and drop-D; future alternate tunings extend [ALL]. */
object BuiltInTunings {
    val STANDARD = Tuning(
        id = "standard",
        displayName = "Standard",
        strings = listOf(
            TuningString(6, "6th String", Note.forMidiNumber(40)), // E2
            TuningString(5, "5th String", Note.forMidiNumber(45)), // A2
            TuningString(4, "4th String", Note.forMidiNumber(50)), // D3
            TuningString(3, "3rd String", Note.forMidiNumber(55)), // G3
            TuningString(2, "2nd String", Note.forMidiNumber(59)), // B3
            TuningString(1, "1st String", Note.forMidiNumber(64)) // E4
        )
    )

    /**
     * Standard with the 6th string dropped a whole step, E2 -> D2; strings
     * 1-5 unchanged. Derived from [STANDARD.strings] (filtered, then D2
     * prepended) rather than re-listing all six notes, so the two tunings
     * can't silently drift apart if [STANDARD] is ever tweaked - this relies
     * on [STANDARD.strings] already being in low-to-high order.
     */
    val DROP_D = Tuning(
        id = "drop-d",
        displayName = "Drop D",
        strings = listOf(TuningString(6, "6th String", Note.forMidiNumber(38))) + // D2
            STANDARD.strings.filter { it.stringNumber != 6 }
    )

    val ALL: List<Tuning> = listOf(STANDARD, DROP_D)

    fun byId(id: String): Tuning = ALL.find { it.id == id } ?: STANDARD
}
