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
        assertEquals(BuiltInTunings.STANDARD, BuiltInTunings.byId("nonexistent"))
        assertEquals(BuiltInTunings.STANDARD, BuiltInTunings.byId("standard"))
    }

    @Test
    fun byIdResolvesDropD() {
        assertEquals(BuiltInTunings.DROP_D, BuiltInTunings.byId("drop-d"))
    }

    @Test
    fun dropDOnlyChangesTheSixthStringFromStandard() {
        val standard = BuiltInTunings.STANDARD.strings.associateBy { it.stringNumber }
        val dropD = BuiltInTunings.DROP_D.strings.associateBy { it.stringNumber }

        assertEquals(setOf(6, 5, 4, 3, 2, 1), dropD.keys)
        for (stringNumber in 1..5) {
            assertEquals(
                standard.getValue(stringNumber),
                dropD.getValue(stringNumber),
                "string $stringNumber must be unchanged from standard tuning"
            )
        }
        assertEquals("D2", dropD.getValue(6).note.name)
        assertTrue(
            abs(dropD.getValue(6).note.frequencyHz() - 73.42f) < 0.05f,
            "expected D2 ~73.42 Hz, was ${dropD.getValue(6).note.frequencyHz()} Hz"
        )
    }
}
