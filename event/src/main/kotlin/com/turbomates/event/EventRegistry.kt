package com.turbomates.event

import com.turbomates.event.seriazlier.EventSerializer
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * The events of an application and how they are written: the declared [Event.Key.name] of each of
 * them mapped to the serializer that reads it back, and the [json] every payload is encoded with.
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
 * An event that declares no name is not held here at all. Its payload keeps the class name it always
 * carried and is read back the old way, so an application in the middle of the migration — or one
 * that never starts it — needs no registration.
 *
 * @param json format of every event payload. Build it on top of [DEFAULT_JSON] to add a serializers
 * module of your own, for example contextual serializers of the value types the events carry, and
 * keep the flags the rows already written were written with:
 * ```
 * EventRegistry(SubscriptionCreated, json = Json(from = EventRegistry.DEFAULT_JSON) { serializersModule = domain })
 * ```
 */
class EventRegistry(vararg keys: Event.Key<*>, val json: Json = DEFAULT_JSON) {
    private val serializers = ConcurrentHashMap<String, KSerializer<Event>>()

    /** Route → the key claiming it, declared and derived names alike, see [claim]. */
    private val routes = ConcurrentHashMap<String, Event.Key<*>>()

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
        claim(key)
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
    fun <T : Event> register(key: Event.Key<T>, serializer: KSerializer<T>): Boolean {
        claim(key)
        return put(key, serializer as KSerializer<Event>)
    }

    /**
     * Claims the route of [key]: its declared name, or the derived one of an event that declares
     * nothing. Two events answering one route are refused no matter how the registry is built —
     * the constructor, [register] by hand, the catalogs of [discovered], the self registration of
     * the rabbit consumers — because every one of those passes through here. A queue bound to a
     * shared route receives every claimant, and the consumer cannot tell one from another.
     */
    private fun claim(key: Event.Key<*>) {
        // The name of an anonymous key that declares nothing throws; such a key cannot be routed or
        // stored at all, and whatever uses it fails on its own account.
        val route = runCatching { key.name }.getOrNull() ?: return
        val previous = routes.putIfAbsent(route, key)
        require(previous == null || previous == key) {
            "Events ${previous?.owner()} and ${key.owner()} are both routed as '$route': a queue " +
                "bound to that route receives both and cannot tell one from another. Give them " +
                "distinct declared names."
        }
    }

    /** The event of a key, for an error message: the class the key is the companion of. */
    private fun Event.Key<*>.owner(): String =
        this::class.java.declaringClass?.name ?: this::class.qualifiedName ?: toString()

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
            "Two serializers answer the name '$name': either two events share it, or one event was " +
                "registered twice with different serializers"
        }
        return true
    }

    /** Serializer of the event stored under [name], null when no event was registered under it. */
    internal operator fun get(name: String): KSerializer<Event>? = serializers[name]

    /**
     * Whether a row written for an event of [key] could be read back through this registry: either
     * the key declares a name registered here, or it declares none and the row carries its class
     * name. What stores events asks this before writing — the storage is read by the application
     * that owns it, so its own registry is the authority. A publish to a broker is not checked
     * against it: those messages are read by the subscribers of other applications, each resolving
     * the name through a registry of its own.
     */
    fun readable(key: Event.Key<*>): Boolean = !key.hasDeclaredName() || serializers.containsKey(key.name)

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

        /**
         * [discovered] over the given catalogs. The catalogs carry keys without declared names too:
         * the registry never holds those — their events are stored under their class names — but
         * their routes exist in the broker all the same, and registering them is what lets [claim]
         * refuse a collision between a declared name and a derived one.
         */
        internal fun of(catalogs: List<EventCatalog>, json: Json = DEFAULT_JSON): EventRegistry {
            val registry = EventRegistry(json = json)
            catalogs.flatMap { it.keys }.distinct().forEach { key -> registry.register(key) }
            return registry
        }
    }
}
