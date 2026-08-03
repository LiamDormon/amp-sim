package org.ampsim.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit

class EventBusImplTest {

    private suspend fun EventBusImpl.awaitSubscribers(count: Int = 1) {
        subscriptionCount.first { it >= count }
    }

    @Test
    fun publishedEventIsDeliveredToASubscriber() = runBlocking {
        val bus = EventBusImpl()
        try {
            val received = async { bus.events.first() }
            bus.awaitSubscribers()

            val event = UIEvent.ChainModified(Chain())
            bus.publish(event)

            assertEquals(event, withTimeout(1000) { received.await() })
        } finally {
            bus.close()
        }
    }

    @Test
    fun eventsArriveInPublishOrder() = runBlocking {
        val bus = EventBusImpl()
        try {
            val received = async { bus.events.take(3).toList() }
            bus.awaitSubscribers()

            val events = listOf(
                UIEvent.UnitRemoved("1"),
                UIEvent.UnitRemoved("2"),
                UIEvent.UnitRemoved("3")
            )
            events.forEach { bus.publish(it) }

            assertEquals(events, withTimeout(1000) { received.await() })
        } finally {
            bus.close()
        }
    }

    @Test
    fun everySubscriberReceivesEveryEvent() = runBlocking {
        val bus = EventBusImpl()
        try {
            val subscriberA = async { bus.events.first() }
            val subscriberB = async { bus.events.first() }
            val subscriberC = async { bus.events.first() }
            bus.awaitSubscribers(count = 3)

            val event = UIEvent.ErrorOccurred("something broke")
            bus.publish(event)

            assertEquals(event, withTimeout(1000) { subscriberA.await() })
            assertEquals(event, withTimeout(1000) { subscriberB.await() })
            assertEquals(event, withTimeout(1000) { subscriberC.await() })
        } finally {
            bus.close()
        }
    }

    @Test
    fun eventTypeExtensionFiltersOutOtherEventTypes() = runBlocking {
        val bus = EventBusImpl()
        try {
            val onlyParameterChanges = async { bus.parameterChanged().first() }
            bus.awaitSubscribers()

            bus.publish(UIEvent.ChainModified(Chain()))
            bus.publish(UIEvent.UnitRemoved("1"))
            val expected = UIEvent.ParameterChanged("1", "drive", 10f)
            bus.publish(expected)

            assertEquals(expected, withTimeout(1000) { onlyParameterChanges.await() })
        } finally {
            bus.close()
        }
    }

    @Test
    fun everyNamedExtensionFiltersToItsOwnType() = runBlocking {
        val bus = EventBusImpl()
        try {
            val chainModified = async { bus.chainModified().first() }
            val unitAdded = async { bus.unitAdded().first() }
            val unitRemoved = async { bus.unitRemoved().first() }
            val errorOccurred = async { bus.errorOccurred().first() }
            bus.awaitSubscribers(count = 4)

            val chainModifiedEvent = UIEvent.ChainModified(Chain())
            val unitAddedEvent = UIEvent.UnitAdded(
                EffectUnit(id = "1", type = "amp", model = "Plexi"),
                0
            )
            val unitRemovedEvent = UIEvent.UnitRemoved("1")
            val errorEvent = UIEvent.ErrorOccurred("boom")

            bus.publish(chainModifiedEvent)
            bus.publish(unitAddedEvent)
            bus.publish(unitRemovedEvent)
            bus.publish(errorEvent)

            assertEquals(chainModifiedEvent, withTimeout(1000) { chainModified.await() })
            assertEquals(unitAddedEvent, withTimeout(1000) { unitAdded.await() })
            assertEquals(unitRemovedEvent, withTimeout(1000) { unitRemoved.await() })
            assertEquals(errorEvent, withTimeout(1000) { errorOccurred.await() })
        } finally {
            bus.close()
        }
    }

    @Test
    fun genericEventsOfTypeFiltersCorrectly() = runBlocking {
        val bus = EventBusImpl()
        try {
            val onlyErrors = async { bus.eventsOfType<UIEvent.ErrorOccurred>().first() }
            bus.awaitSubscribers()

            bus.publish(UIEvent.ChainModified(Chain()))
            val expected = UIEvent.ErrorOccurred("boom")
            bus.publish(expected)

            assertEquals(expected, withTimeout(1000) { onlyErrors.await() })
        } finally {
            bus.close()
        }
    }

    @Test
    fun noEventIsLostUnderABurstOfPublishes() = runBlocking {
        val bus = EventBusImpl()
        try {
            val eventCount = 5000
            val received = async { bus.events.take(eventCount).toList() }
            bus.awaitSubscribers()

            val published = (0 until eventCount).map { UIEvent.UnitRemoved(it.toString()) }
            // Publish from several threads at once to exercise the "thread-safe
            // delivery" requirement, not just a tight single-threaded loop.
            published.chunked(eventCount / 10).map { chunk ->
                Thread { chunk.forEach { bus.publish(it) } }.apply { start() }
            }.forEach { it.join() }

            val actual = withTimeout(10_000) { received.await() }
            assertEquals(eventCount, actual.size)
            assertEquals(published.toSet(), actual.toSet())
        } finally {
            bus.close()
        }
    }

    @Test
    fun eventPublishedBeforeCloseIsStillDelivered() = runBlocking {
        val bus = EventBusImpl()
        val received = async { bus.events.first() }
        bus.awaitSubscribers()

        val event = UIEvent.UnitRemoved("before-close")
        bus.publish(event)

        assertEquals(event, withTimeout(1000) { received.await() })
        bus.close()
    }
}
