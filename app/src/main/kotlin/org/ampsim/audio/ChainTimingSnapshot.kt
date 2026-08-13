package org.ampsim.audio

/**
 * Per-module CPU timing for one generation of the active chain, paired with
 * the unit id each array slot belongs to.
 *
 * Built off the real-time thread (in lockstep with the [org.ampsim.dsp.DSPModule]
 * list itself) and swapped in as a single [AudioEngine]-owned `@Volatile`
 * reference whenever the chain reloads. Bundling [unitIds] and [cpuLoadPerUnit]
 * into one object — rather than two separately-`@Volatile` fields — means a
 * control-thread read can never observe [unitIds] from one chain generation
 * paired with [cpuLoadPerUnit] from another: swapping either half of a torn
 * pair produces the empty case, which just reads as no measurements taken.
 *
 * [cpuLoadPerUnit] is written in place by [AudioEngine.process] every block
 * (one element per module, no allocation) — never resized or replaced outside
 * of a full chain reload.
 */
class ChainTimingSnapshot(val unitIds: List<String>, val cpuLoadPerUnit: FloatArray) {
    companion object {
        val EMPTY = ChainTimingSnapshot(emptyList(), FloatArray(0))
    }
}
