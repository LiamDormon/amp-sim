package org.ampsim.tuner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuningTest {

    @Test
    fun standardTuningHasSixStringsInLowToHighOrder() {
        val strings = BuiltInTunings.STANDARD.strings
        assertEquals(6, strings.size)
        assertEquals(listOf(6, 5, 4, 3, 2, 1), strings.map { it.stringNumber })
    }

    @Test
    fun standardTuningNotesMatchRealWorldStandardTuning() {
        val expected = listOf(
            6 to "E2" to 82.41f,
            5 to "A2" to 110.00f,
            4 to "D3" to 146.83f,
            3 to "G3" to 196.00f,
            2 to "B3" to 246.94f,
            1 to "E4" to 329.63f
        )

        for ((stringAndNote, expectedHz) in expected) {
            val (stringNumber, expectedName) = stringAndNote
            val string = BuiltInTunings.STANDARD.strings.first { it.stringNumber == stringNumber }
            assertEquals(expectedName, string.note.name)
            assertTrue(
                abs(string.note.frequencyHz() - expectedHz) < 0.05f,
                "expected ${string.note.name} ~$expectedHz Hz, was ${string.note.frequencyHz()} Hz"
            )
        }
    }

    @Test
    fun byIdFallsBackToStandardForAnUnknownId() {
        assertEquals(BuiltInTunings.STANDARD, BuiltInTunings.byId("drop-d"))
        assertEquals(BuiltInTunings.STANDARD, BuiltInTunings.byId("standard"))
    }
}
