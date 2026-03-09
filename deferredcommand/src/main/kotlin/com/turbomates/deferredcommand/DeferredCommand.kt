package com.turbomates.deferredcommand

import com.turbomates.deferredcommand.serializer.LocalDateTimeSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

@Serializable
abstract class DeferredCommand {
    @Serializable(with = LocalDateTimeSerializer::class)
    val timestamp: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    @Serializable(with = LocalDateTimeSerializer::class)
    abstract val executeAt: LocalDateTime

    abstract val key: Key<out DeferredCommand>

    interface Key<T : DeferredCommand>
}
