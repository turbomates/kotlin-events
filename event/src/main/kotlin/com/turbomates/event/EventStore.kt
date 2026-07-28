package com.turbomates.event

import java.util.LinkedList

class EventStore {
    private val events: LinkedList<Event> = LinkedList()
    fun addEvent(event: Event) {
        events.add(event)
    }

    /** Events in the order they were added, so the outbox writes them the way they were raised. */
    fun raiseEvents(): Sequence<Event> = sequence {
        while (events.isNotEmpty()) {
            yield(events.poll())
        }
    }
}
