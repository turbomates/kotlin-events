package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.TraceInformation

/**
 * An event on its way to the outbox: what the row is built from, not a payload of its own. The
 * columns are written one by one by [Outbox.batchEventsInsert], and the event itself goes through
 * the serializer of the [com.turbomates.event.EventRegistry] the outbox holds. The row is keyed by
 * [Event.eventId], the same id the payload carries.
 */
data class PublicEvent(
    val original: Event,
    val traceInformation: TraceInformation? = null
) {
    val id get() = original.eventId
    val createdAt get() = original.timestamp
}
