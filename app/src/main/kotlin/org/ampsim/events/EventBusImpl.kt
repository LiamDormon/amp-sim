package org.ampsim.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * [UIEventBus] backed by a [Channel]: [publish] sends into the channel — a
 * fast, thread-safe, non-suspending operation that never fails since the
 * channel has an unlimited buffer, so a burst of publishes from any thread is
 * never dropped even if subscribers are momentarily slow to keep up. A single
 * internal coroutine drains that channel into a [MutableSharedFlow], which is
 * what actually fans each event out to every current subscriber of [events].
 *
 * A plain [Channel] can't do that fan-out on its own: each element sent to a
 * `Channel` is delivered to exactly one receiver, so with more than one
 * subscriber it would split events between them rather than broadcast. The
 * drain step is what turns that single-consumer delivery into true multicast.
 */
class EventBusImpl(
    replay: Int = 0,
    extraBufferCapacity: Int = DEFAULT_SUBSCRIBER_BUFFER
) : UIEventBus {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<UIEvent>(capacity = Channel.UNLIMITED)

    private val _events = MutableSharedFlow<UIEvent>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity
    )
    override val events = _events.asSharedFlow()

    /** Test-only hook: lets a test await a collector actually attaching before publishing. */
    internal val subscriptionCount get() = _events.subscriptionCount

    init {
        scope.launch {
            for (event in channel) {
                _events.emit(event)
            }
        }
    }

    override fun publish(event: UIEvent) {
        // Channel.UNLIMITED accepts unconditionally (fails only if the channel
        // is already closed), so no publish is ever silently dropped here.
        channel.trySend(event)
    }

    /** Stop draining the channel and release the internal coroutine. */
    fun close() {
        channel.close()
        scope.cancel()
    }

    companion object {
        private const val DEFAULT_SUBSCRIBER_BUFFER = 64
    }
}
