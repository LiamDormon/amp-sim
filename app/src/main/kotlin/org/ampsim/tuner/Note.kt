package org.ampsim.tuner

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A chromatic pitch identified by MIDI note number (69 = A4). Frequency is
 * always derived from [midiNumber] against a caller-supplied reference pitch
 * rather than stored, so changing the reference (e.g. A440 -> A432) recomputes
 * every note's frequency with no data migration.
 */
data class Note(val name: String, val midiNumber: Int) {

    fun frequencyHz(referenceA4Hz: Float = 440f): Float =
        referenceA4Hz * 2f.pow((midiNumber - A4_MIDI_NUMBER) / 12f)

    companion object {
        private const val A4_MIDI_NUMBER = 69
        private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun forMidiNumber(midiNumber: Int): Note {
            val name = NOTE_NAMES[Math.floorMod(midiNumber, 12)]
            val octave = midiNumber / 12 - 1
            return Note(name = "$name$octave", midiNumber = midiNumber)
        }

        /** Rounds [frequencyHz] to the nearest chromatic [Note] under [referenceA4Hz]. */
        fun nearestTo(frequencyHz: Float, referenceA4Hz: Float = 440f): Note {
            val midi = A4_MIDI_NUMBER + 12 * log2(frequencyHz / referenceA4Hz)
            return forMidiNumber(midi.roundToInt())
        }
    }
}
