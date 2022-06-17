package com.turbomates.event

import java.util.LinkedList

class EventStore {
    private val events: LinkedList<Event> = LinkedList()
    fun addEvent(event: Event) {
        events.push(event)
    }

    fun raiseEvents(): Sequence<Event> = sequence {
        while (events.isNotEmpty()) {
            yield(events.pop())
        }
    }
}
