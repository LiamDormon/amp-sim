package org.ampsim.events

import kotlinx.coroutines.flow.Flow

/**
 * Publish/subscribe bus for [UIEvent]s, decoupling components that produce
 * state changes from the (possibly several) components that react to them.
 *
 * [events] is a hot stream: a subscriber receives events published from the
 * moment it starts collecting onward, not a replay of history.
 */
interface UIEventBus {

    /** Every event published to this bus, from the moment a collector subscribes. */
    val events: Flow<UIEvent>

    /** Publish [event] to every current subscriber. Safe to call from any thread. */
    fun publish(event: UIEvent)
}
