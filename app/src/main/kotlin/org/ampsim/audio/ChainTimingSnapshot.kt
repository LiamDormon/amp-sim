package org.ampsim.audio

import org.ampsim.dsp.DSPModule

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

/**
 * The active chain's module list and its [ChainTimingSnapshot], bundled into
 * one object behind a single [AudioEngine]-owned `@Volatile` reference.
 *
 * [modules] and [timing] must always describe the same chain generation —
 * [timing]'s arrays are sized/ordered to match [modules] 1:1. Before this
 * type existed, `AudioEngine` held them as two separately-`@Volatile` fields,
 * which let a reader on another thread (e.g. [AudioEngine.restart]) observe
 * [modules] from one chain generation paired with [timing] from the next,
 * torn across the gap between the two reads — exactly the hazard
 * [ChainTimingSnapshot]'s own bundling was meant to prevent, just one level
 * up. One field, swapped as a whole on every reload, closes that gap.
 */
class ActiveChainState(val modules: List<DSPModule>, val timing: ChainTimingSnapshot) {
    companion object {
        val EMPTY = ActiveChainState(emptyList(), ChainTimingSnapshot.EMPTY)
    }
}
