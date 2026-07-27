package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.seriazlier.EventSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * How events are written to the `jsonb` columns of `outbox_events` and `event_sourcing`.
 *
 * The columns bind their format when the tables are first touched, so [configure] belongs to the
 * startup of the application, before the first event is written or published. Give it a [Json] built
 * on top of [DEFAULT] to keep a serializers module of your own, for example contextual serializers of
 * the value types your events carry:
 *
 * ```
 * EventSerialization.configure(
 *     Json(from = EventSerialization.DEFAULT) { serializersModule = domainSerializers }
 * )
 * ```
 *
 * The [KSerializer] is the one that decides what a row looks like, [EventSerializer] writes
 * `{"type": <class>, "body": {...}}`. Replacing it changes the format of every row, so an outbox and
 * an event sourcing table already holding data can only be read back by a serializer that understands
 * what is in them.
 */
object EventSerialization {
    val DEFAULT: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    @Volatile
    private var format: Json = DEFAULT

    @Volatile
    private var events: KSerializer<Event> = EventSerializer

    @Volatile
    private var inUse: Boolean = false

    /**
     * @throws EventSerializationInUseException when the tables already bound their format, which means
     * this call came too late to have any effect.
     */
    fun configure(json: Json = DEFAULT, serializer: KSerializer<Event> = EventSerializer) {
        if (inUse) {
            throw EventSerializationInUseException()
        }
        format = json
        events = serializer
    }

    internal fun json(): Json {
        inUse = true
        return format
    }

    internal fun serializer(): KSerializer<Event> {
        inUse = true
        return events
    }

    internal fun reset() {
        format = DEFAULT
        events = EventSerializer
        inUse = false
    }
}

class EventSerializationInUseException : IllegalStateException(
    "Event serialization is already in use, the outbox tables bound their format on first use. " +
        "Call EventSerialization.configure() at startup, before the first event is written or published."
)
