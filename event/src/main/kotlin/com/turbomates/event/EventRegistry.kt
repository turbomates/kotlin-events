package com.turbomates.event

import com.turbomates.event.seriazlier.EventSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Maps the declared [Event.Key.name] of an event to the serializer that reads it back.
 *
 * A stored payload carries a name, and a name is all a reader has: there is no way from
 * `"billing.subscription.created"` to a class other than a table somebody filled, and resolving it
 * by loading the class the payload is named after is what ties the stored data to the shape of the
 * code. Build one registry at startup and hand it to everything that reads events — the outbox, the
 * event sourcing storage, the rabbit consumer — they have to agree on it:
 *
 * ```
 * val events = EventRegistry(SubscriptionCreated, SubscriptionCancelled)
 * val outbox = Outbox(bucketCount = 16, serialization = EventSerialization(events = events))
 * val queue = RabbitQueue(config, json, subscribers, scope, telemetry, events = events)
 * ```
 *
 * Events consumed from the broker register themselves: [com.turbomates.event.rabbit.RabbitQueue]
 * knows the key of every subscriber it starts. Events the application publishes have no such list,
 * they are the ones to pass here.
 *
 * An event that declares no name is not held here at all. Its payload keeps the class name it always
 * carried and is read back the old way, so an application in the middle of the migration — or one
 * that never starts it — needs no registry.
 */
class EventRegistry(vararg keys: Event.Key<*>) {
    private val serializers = ConcurrentHashMap<String, KSerializer<Event>>()

    /**
     * Serializer of the stored payload, `{"type": .., "body": {..}}`, resolving the `type` through
     * this registry. One instance per registry, hand it to whatever writes or reads events.
     */
    val serializer: KSerializer<Event> by lazy { EventSerializer(this) }

    init {
        keys.forEach { register(it) }
    }

    /**
     * Registers the event of [key] under its declared name, taking the serializer from the class the
     * key is the companion object of.
     *
     * @return false when [key] declares no name, in which case there is nothing to hold: the event
     * is stored under its class name and read back by it.
     * @throws IllegalArgumentException when the key declares a name but is not the companion object
     * of its event — pass the serializer explicitly for such a key.
     */
    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    fun register(key: Event.Key<*>): Boolean {
        if (!key.hasDeclaredName()) return false
        val eventClass = key::class.java.declaringClass
        require(eventClass != null && Event::class.java.isAssignableFrom(eventClass)) {
            "The key of event '${key.name}' is not the companion object of its event, so its " +
                "serializer cannot be found. Register it with the serializer of the event."
        }
        return put(key, (eventClass.kotlin as KClass<Event>).serializer())
    }

    /**
     * Registers [serializer] under the declared name of [key]. Registering the same event twice is
     * fine, two events answering one name is not: the second one read back would be the first one.
     *
     * @return false when [key] declares no name, see [register].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Event> register(key: Event.Key<T>, serializer: KSerializer<T>): Boolean =
        put(key, serializer as KSerializer<Event>)

    private fun put(key: Event.Key<*>, serializer: KSerializer<Event>): Boolean {
        if (!key.hasDeclaredName()) return false
        val name = key.name
        require(NAME.matches(name)) {
            "Event name '$name' has to be dot separated, snake_case inside a segment and at least " +
                "two segments long, e.g. 'billing.subscription.created': the dots are the hierarchy " +
                "a topic exchange matches a binding like 'billing.#' on."
        }
        require(name.toByteArray().size <= MAX_NAME_LENGTH) {
            "Event name '$name' is longer than the $MAX_NAME_LENGTH bytes a routing key can hold"
        }
        val previous = serializers.putIfAbsent(name, serializer)
        require(previous == null || previous == serializer) {
            "Two events are named '$name', the one stored under it could not be told from the other"
        }
        return true
    }

    /** Serializer of the event stored under [name], null when no event was registered under it. */
    operator fun get(name: String): KSerializer<Event>? = serializers[name]

    /**
     * Whether an event of [key] can be read back once it is written: either it declares a name and
     * that name is registered here, or it declares none and is stored under its class name.
     *
     * A declared name that is missing here is a row nobody can decode — the outbox would carry it
     * until it is deleted by hand, and an event sourced aggregate would never be rebuilt again.
     */
    fun readable(key: Event.Key<*>): Boolean = !key.hasDeclaredName() || serializers.containsKey(key.name)

    private companion object {
        val NAME = Regex("^[a-z0-9]+(_[a-z0-9]+)*(\\.[a-z0-9]+(_[a-z0-9]+)*)+$")

        /** A routing key of RabbitMQ holds 255 bytes, and the name is published as one. */
        const val MAX_NAME_LENGTH = 255
    }
}
