package com.turbomates.event

import com.turbomates.event.seriazlier.EventSerializer
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer

/**
 * The events of an application and how they are written: the [Event.Key.name] of each of them mapped
 * to the serializer that reads it back, and the [json] every payload is encoded with.
 *
 * A stored payload carries a name, and a name is all a reader has: there is no way from
 * `"billing.subscription.created"` to a class other than a table somebody filled, and resolving it
 * by loading the class the payload is named after is what ties the stored data to the shape of the
 * code. Build one registry at startup and hand it to everything that writes or reads events — the
 * outbox, the event sourcing storage, the rabbit publisher and consumer. They have to agree on all
 * of it, which is why it travels as one object rather than as a registry and a format that could be
 * given out separately and drift apart:
 *
 * ```
 * val events = EventRegistry(SubscriptionCreated, SubscriptionCancelled)
 * val outbox = Outbox(bucketCount = 16, events = events)
 * val queue = RabbitQueue(config, subscribers, scope, telemetry, events = events)
 * ```
 *
 * Events consumed from the broker register themselves: [com.turbomates.event.rabbit.RabbitQueue]
 * knows the key of every subscriber it starts. Events the application publishes have no such list,
 * they are the ones to pass here.
 *
 * Every event belongs here, whether it declares a name or falls back to the one derived from its
 * class: the name is what the payload carries either way, so a name nothing is registered under is
 * a row nothing can read. That is what makes the `event-ksp` catalog exhaustive rather than a list
 * of the events that opted in — see [discovered].
 *
 * @param json format of every event payload. Build it on top of [DEFAULT_JSON] to add a serializers
 * module of your own, for example contextual serializers of the value types the events carry, and
 * keep the flags the rows already written were written with:
 * ```
 * EventRegistry(SubscriptionCreated, json = Json(from = EventRegistry.DEFAULT_JSON) { serializersModule = domain })
 * ```
 * The registry adds [serializer] to it as the contextual serializer of [Event], see [format].
 */
class EventRegistry(vararg keys: Event.Key<*>, json: Json = DEFAULT_JSON) {
    private val serializers = ConcurrentHashMap<String, KSerializer<Event>>()

    /**
     * Serializer of the stored payload, `{"type": .., "body": {..}}`, resolving the `type` through
     * this registry — what [encode] and [decode] write and read with, and the contextual serializer
     * of [Event] in [format].
     */
    internal val serializer: KSerializer<Event> = EventSerializer(this)

    /**
     * The json of every payload: the one the registry was built with, plus [serializer] registered
     * as the contextual serializer of [Event].
     *
     * An event that carries another one — a delivery that failed, the event a saga step reacts to,
     * anything wrapping an event it did not declare the type of — writes the field as
     * ```
     * @Contextual val originalEvent: Event
     * ```
     * and the nested event is stored as the same `{"type": .., "body": {..}}` as the row around it,
     * its name resolved through this registry. Without the registration such a field has no
     * serializer at all: an `Event` is read back from a name, and the table from a name to a
     * serializer is the registry — which is why the knot is tied here, where the table is, rather
     * than by every application building an [EventSerializer] over a registry of its own and
     * hoping it is the same one.
     *
     * A serializers module that registers a contextual [Event] of its own keeps it: this fills a
     * slot nobody filled, it does not claim one.
     */
    private val format: Json = Json(from = json) {
        serializersModule = SerializersModule { contextual(Event::class, serializer) }
            .overwriteWith(json.serializersModule)
    }

    init {
        keys.forEach { register(it) }
    }

    /**
     * Registers the event of [key] under its name, taking the serializer from the class the key is
     * the companion object of.
     *
     * @throws IllegalArgumentException when the key is not the companion object of its event — pass
     * the serializer explicitly for such a key.
     */
    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    fun register(key: Event.Key<*>) {
        val eventClass = key::class.java.declaringClass
        require(eventClass != null && Event::class.java.isAssignableFrom(eventClass)) {
            "The key of event '${key.name}' is not the companion object of its event, so its " +
                "serializer cannot be found. Register it with the serializer of the event."
        }
        put(key, (eventClass.kotlin as KClass<Event>).serializer())
    }

    /**
     * Registers [serializer] under the name of [key], which is then how the event is written as well
     * as read — a serializer of your own replaces the generated one in both directions.
     *
     * Registering the same event twice is fine and the first registration stands, two events
     * answering one name is not: the second one read back would be the first one.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Event> register(key: Event.Key<T>, serializer: KSerializer<T>) =
        put(key, serializer as KSerializer<Event>)

    private fun put(key: Event.Key<*>, serializer: KSerializer<Event>) {
        val name = key.name
        // The form is asked of a declared name only. A derived one is not authored — it is the
        // shape of the code, and it has been the routing key of that event all along, so refusing
        // it here would fail the startup of an application that changed nothing.
        require(!key.hasDeclaredName() || NAME.matches(name)) {
            "Event name '$name' has to be dot separated, snake_case inside a segment and at least " +
                "two segments long, e.g. 'billing.subscription.created': the dots are the hierarchy " +
                "a topic exchange matches a binding like 'billing.#' on."
        }
        require(name.toByteArray().size <= MAX_NAME_LENGTH) {
            "Event name '$name' is longer than the $MAX_NAME_LENGTH bytes a routing key can hold"
        }
        val previous = serializers.putIfAbsent(name, serializer)
        // Two registrations of one name are the same registration when they read the same class.
        // Neither the serializers nor the keys answer that: a KSerializer has no equals, and a
        // generic event builds a fresh one on every serializer() call, while a key is the companion
        // object of its event in a catalog and a key written for the occasion when it is registered
        // by hand. The serial name of the descriptor is the class the payload is read as, which is
        // the whole of what this table holds.
        if (previous != null) {
            require(previous.descriptor.serialName == serializer.descriptor.serialName) {
                "The name '$name' is already the name of ${previous.descriptor.serialName}, " +
                    "${serializer.descriptor.serialName} cannot answer it too: a stored payload " +
                    "carries its name and nothing else, so one of the two read back would be the other."
            }
        }
    }

    /** The stored form of [event]: `{"type": .., "body": {..}}` in the json of this registry. */
    fun encode(event: Event): String = format.encodeToString(serializer, event)

    /** The event a stored payload holds, its `type` resolved through this registry. */
    fun decode(payload: String): Event = format.decodeFromString(serializer, payload)

    /** Serializer of the event stored under [name], null when no event was registered under it. */
    internal operator fun get(name: String): KSerializer<Event>? = serializers[name]

    /** Whether an event is registered under [name], however it got there. */
    operator fun contains(name: String): Boolean = serializers.containsKey(name)

    companion object {
        val DEFAULT_JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        private val NAME = Regex("^[a-z0-9]+(_[a-z0-9]+)*(\\.[a-z0-9]+(_[a-z0-9]+)*)+$")

        /** A routing key of RabbitMQ holds 255 bytes, and the name is published as one. */
        private const val MAX_NAME_LENGTH = 255

        /**
         * The registry of every event compiled with the `event-ksp` processor: the [EventCatalog]s
         * on the classpath, folded into one registry. With the processor on every module that
         * declares events, this is the whole setup — the table from a name back to a serializer is
         * maintained by the compiler, and there is no registration to forget:
         *
         * ```
         * val events = EventRegistry.discovered()
         * ```
         *
         * Events from a jar compiled without the processor, and keys that are not the companion of
         * their event, are the two things a catalog cannot carry — [register] them on the returned
         * registry.
         */
        fun discovered(json: Json = DEFAULT_JSON): EventRegistry =
            of(ServiceLoader.load(EventCatalog::class.java).toList(), json)

        /** [discovered] over the given catalogs, the same event met in several of them is fine. */
        internal fun of(catalogs: List<EventCatalog>, json: Json = DEFAULT_JSON): EventRegistry {
            val registry = EventRegistry(json = json)
            catalogs.flatMap { it.keys }.distinct().forEach { key -> registry.register(key) }
            return registry
        }
    }
}
