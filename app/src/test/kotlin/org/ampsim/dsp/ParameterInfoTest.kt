package org.ampsim.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParameterInfoTest {

    @Test
    fun kindDefaultsToContinuousLinear() {
        val info = ParameterInfo(name = "drive", min = 0f, max = 10f, default = 5f)
        assertEquals(ParameterKind.CONTINUOUS_LINEAR, info.kind)
        assertEquals(0f, info.step)
        assertEquals(emptyList(), info.choices)
    }

    @Test
    fun clampIsUnaffectedByKind() {
        val linear = ParameterInfo(name = "drive", min = 0f, max = 10f, default = 5f)
        val log = ParameterInfo(name = "freq", min = 20f, max = 20000f, default = 1000f, kind = ParameterKind.CONTINUOUS_LOG)

        assertEquals(10f, linear.clamp(100f))
        assertEquals(0f, linear.clamp(-5f))
        assertEquals(20000f, log.clamp(50000f))
    }

    @Test
    fun booleanParameterRequiresZeroToOneRange() {
        // Valid: exactly [0, 1].
        ParameterInfo(name = "bright", min = 0f, max = 1f, default = 0f, kind = ParameterKind.BOOLEAN)

        assertFailsWith<IllegalArgumentException> {
            ParameterInfo(name = "bright", min = 0f, max = 10f, default = 0f, kind = ParameterKind.BOOLEAN)
        }
    }

    @Test
    fun choiceParameterRequiresChoicesAndMatchingRange() {
        // Valid: 3 choices -> [0, 2].
        ParameterInfo(
            name = "mode",
            min = 0f,
            max = 2f,
            default = 0f,
            kind = ParameterKind.CHOICE,
            choices = listOf("Clean", "Crunch", "Lead")
        )

        assertFailsWith<IllegalArgumentException> {
            ParameterInfo(name = "mode", min = 0f, max = 2f, default = 0f, kind = ParameterKind.CHOICE, choices = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            ParameterInfo(
                name = "mode",
                min = 0f,
                max = 5f,
                default = 0f,
                kind = ParameterKind.CHOICE,
                choices = listOf("Clean", "Crunch", "Lead")
            )
        }
    }

    @Test
    fun stepMustNotBeNegative() {
        assertFailsWith<IllegalArgumentException> {
            ParameterInfo(name = "drive", min = 0f, max = 10f, default = 5f, step = -1f)
        }
    }

    @Test
    fun snapToStepIsANoOpWhenStepIsZero() {
        val info = ParameterInfo(name = "drive", min = 0f, max = 10f, default = 5f)
        assertEquals(5.37f, info.snapToStep(5.37f))
    }

    @Test
    fun snapToStepRoundsToTheNearestIncrementFromMin() {
        val info = ParameterInfo(name = "mode", min = 0f, max = 10f, default = 0f, step = 2f)

        assertEquals(0f, info.snapToStep(0.9f))
        assertEquals(2f, info.snapToStep(1.1f))
        assertEquals(4f, info.snapToStep(3f))
    }

    @Test
    fun snapToStepClampsBeforeSnapping() {
        val info = ParameterInfo(name = "mode", min = 0f, max = 10f, default = 0f, step = 3f)

        // Clamped to 10 first, then snapped to the nearest step from min (9,
        // since 10 isn't itself a multiple of the 3-wide step from 0).
        assertEquals(9f, info.snapToStep(100f))
        assertEquals(0f, info.snapToStep(-100f))
    }
}
