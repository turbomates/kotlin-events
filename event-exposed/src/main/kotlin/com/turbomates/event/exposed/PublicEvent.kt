package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.TraceInformation
import java.util.UUID

/**
 * An event on its way to the outbox: what the row is built from, not a payload of its own. The
 * columns are written one by one by [Outbox.batchEventsInsert], and the event itself goes through
 * the serializer of the [EventSerialization] the outbox holds.
 */
data class PublicEvent(
    val original: Event,
    val id: UUID = uuidV7(),
    val traceInformation: TraceInformation? = null
) {
    val createdAt = original.timestamp
}
