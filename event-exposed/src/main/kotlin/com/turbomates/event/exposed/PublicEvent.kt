package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.TraceInformation
import com.turbomates.event.exposed.serializer.UUIDSerializer
import com.turbomates.event.seriazlier.EventSerializer
import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class PublicEvent(
    @Serializable(with = EventSerializer::class)
    val original: Event,
    @Serializable(with = UUIDSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val traceInformation: TraceInformation? = null
) {
    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt = original.timestamp

    /** Outbox bucket of the event, its partition key when it has one, its own id otherwise. */
    val bucket: Int get() = OutboxBuckets.of(original.partitionKey, id)
}

