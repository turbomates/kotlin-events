package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.exposed.serializer.UUIDSerializer
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
abstract class EventSourcingEvent(@Serializable(with = UUIDSerializer::class) val rootId: UUID) : Event()
