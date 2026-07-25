package com.turbomates.event

import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
abstract class Event {
    @Serializable(with = LocalDateTimeSerializer::class)
    val timestamp: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
    abstract val key: Key<out Event>

    /**
     * Identifies the stream the event belongs to, e.g. a user or an account id.
     *
     * Events sharing a partition key are kept together by the transports that support partitioning
     * (the Exposed outbox assigns them to the same bucket), so they are never processed by two
     * workers at the same time. Events without a partition key fall back to their own outbox id,
     * which spreads them evenly across buckets.
     *
     * Override with a getter so the property stays out of the serialized payload:
     * ```
     * override val partitionKey get() = userId
     * ```
     */
    @Transient
    open val partitionKey: UUID? = null

    interface Key<T : Event>
}
