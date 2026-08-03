package org.ampsim.audio

import org.ampsim.dsp.DSPModule

/**
 * A message sent from a control/UI thread to the real-time audio thread.
 *
 * Commands are enqueued into a [LockFreeRingBuffer] and drained by the audio
 * thread at the start of every processing block, so their effects always take
 * place on a block boundary. This avoids parameter changes taking effect
 * mid-block, which is the classic source of clicks and pops.
 *
 * All payloads are prepared (and any DSP modules pre-allocated) on the control
 * thread; applying a command on the audio thread never allocates.
 */
sealed interface AudioCommand {

    /**
     * Replace the entire active DSP chain. The [modules] list and every module
     * in it must be fully constructed and pre-warmed off the audio thread.
     */
    data class LoadChain(val modules: List<DSPModule>) : AudioCommand

    /**
     * Update a single parameter of the module at [index] in the active chain.
     * Out-of-range indices are ignored by the audio thread.
     */
    data class SetParameter(val index: Int, val name: String, val value: Float) : AudioCommand

    /** Reset the internal state (filters, delay lines) of every active module. */
    data object ResetChain : AudioCommand

    /** Enable or disable audio output (metering keeps running when disabled). */
    data class SetPlayback(val enabled: Boolean) : AudioCommand
}
