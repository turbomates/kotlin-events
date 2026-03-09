package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.exposed.serializer.UUIDSerializer
import com.turbomates.deferredcommand.serializer.DeferredCommandSerializer
import com.turbomates.deferredcommand.serializer.LocalDateTimeSerializer
import com.turbomates.event.TraceInformation
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledDeferredCommand(
    @Serializable(with = DeferredCommandSerializer::class)
    val original: DeferredCommand,
    @Serializable(with = UUIDSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val traceInformation: TraceInformation? = null,
) {
    @Serializable(with = LocalDateTimeSerializer::class)
    val executeAt = original.executeAt

    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt = original.timestamp
}
