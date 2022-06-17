package com.turbomates.event.exposed

import com.turbomates.event.Event
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
    val id: UUID = UUID.randomUUID()
) {
    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt = original.timestamp
}

