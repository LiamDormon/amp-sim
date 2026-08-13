package org.ampsim.tuner

/** The Tuner tab's current comparison mode. */
sealed interface TunerMode {
    /** Compare the detected pitch against whatever chromatic note is nearest. */
    data object Auto : TunerMode

    /** Compare the detected pitch against a fixed, user-selected [target] regardless of proximity. */
    data class Manual(val target: TuningString) : TunerMode
}
