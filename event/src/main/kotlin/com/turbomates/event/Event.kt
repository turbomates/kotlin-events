package com.turbomates.event

import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import com.turbomates.event.seriazlier.UUIDSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
abstract class Event {
    /**
     * Identity of this occurrence of the event, a UUIDv7 assigned when the event is created.
     *
     * It is the `id` of the outbox and event sourcing rows the event is stored as and it travels
     * in the payload, so the consumer reads the very id the producer wrote instead of one made
     * up on the way — a redelivery is recognised by it. Generated, never assigned by hand.
     */
    @Serializable(with = UUIDSerializer::class)
    val eventId: UUID = uuidV7()

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

    interface Key<T : Event> {
        /**
         * Stable identity of the event: the routing key it is published under and the `type` of the
         * stored payload.
         *
         * Declare it by hand and never change it again. It is what keeps a rename or a move of the
         * event class from orphaning the bindings of the queues that consume it and from making the
         * rows already written unreadable — the whole point of the property is that it does not
         * follow the code:
         * ```
         * companion object : Key<SubscriptionCreated> {
         *     override val name = "billing.subscription.created"
         * }
         * ```
         *
         * Dot separated, from the general to the specific, `snake_case` inside a segment, at least
         * two segments. The dots are the hierarchy a topic exchange matches on: a flat name is never
         * caught by a `billing.#` binding, and the form cannot be corrected later without migrating
         * every binding and every stored row.
         *
         * The default derives the name from the class of the key, which is what the routes of this
         * library looked like before names were declared, so an application that declares nothing
         * keeps publishing and consuming exactly what it did. A derived name is as fragile as the
         * class it comes from — what it is not is a different mechanism: declared or derived, the
         * name is the routing key, the stored `type` and the [EventRegistry] entry, see
         * [derivedName].
         */
        @Suppress("DEPRECATION")
        val name: String get() = derivedName()
    }
}
