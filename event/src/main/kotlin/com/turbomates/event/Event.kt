package com.turbomates.event

import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

@Serializable
abstract class Event {
    @Serializable(with = LocalDateTimeSerializer::class)
    val timestamp: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC);
    abstract val key: Key<out Event>

    interface Key<T : Event>
}
