package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.TraceInformation
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The outbox of an application: how many buckets its rows are written with, how the events are
 * serialized, and the interceptor that writes them. Build it once at startup and hand it to both the
 * interceptor and the [OutboxPublisher], they have to agree on all of it.
 *
 * ```
 * val outbox = Outbox(bucketCount = 16)
 * outbox.install()
 * val publisher = OutboxPublisher(database, publishers, outbox)
 * ```
 *
 * @param bucketCount buckets the outbox is written with. It describes the rows already in
 * `outbox_events`, changing it for a non empty outbox re-maps every partition key, so events of one
 * stream would sit in two buckets at once and could be published by two workers in parallel. Every
 * process of the application has to be built with the same count.
 */
class Outbox(
    val bucketCount: Int,
    val serialization: EventSerialization = EventSerialization()
) {
    init {
        require(bucketCount in 1..MAX_BUCKET_COUNT) {
            "outbox bucket count must be in 1..$MAX_BUCKET_COUNT, got $bucketCount"
        }
    }

    internal val events = EventsTable(serialization)
    internal val eventSourcing = EventSourcingTable(serialization)

    /** Bucket of an outbox row: the event partition key when it has one, its own id otherwise. */
    fun bucket(partitionKey: UUID?, eventId: UUID): Int = bucket(partitionKey ?: eventId)

    fun bucket(key: UUID): Int =
        Math.floorMod(key.mostSignificantBits xor key.leastSignificantBits, bucketCount)

    fun interceptor(): OutboxInterceptor = OutboxInterceptor(this)

    /**
     * Registers [interceptor] for every transaction of the process, which is what makes events raised
     * in a transaction reach `outbox_events`. Call it once, at startup.
     *
     * @return the registered interceptor, to remove it from [JdbcTransaction.globalInterceptors] again.
     */
    fun install(): OutboxInterceptor {
        check(JdbcTransaction.globalInterceptors.none { it is OutboxInterceptor }) {
            "An outbox interceptor is already installed, every event would be written twice"
        }
        return interceptor().also { JdbcTransaction.globalInterceptors.add(it) }
    }

    companion object {
        const val MAX_BUCKET_COUNT: Int = 256
    }
}

/**
 * Format of the `jsonb` columns holding events. Build the [json] on top of [DEFAULT_JSON] to add a
 * serializers module of your own, for example contextual serializers of the value types the events
 * carry, and keep the flags the existing rows were written with:
 *
 * ```
 * EventSerialization(Json(from = EventSerialization.DEFAULT_JSON) { serializersModule = domain })
 * ```
 *
 * [serializer] decides what a row looks like, [EventSerializer] writes `{"type": .., "body": {..}}`.
 * A table that already holds rows can only be read back by a serializer that understands them, so
 * replacing it is a migration, not a setting.
 */
data class EventSerialization(
    val json: Json = DEFAULT_JSON,
    val serializer: KSerializer<Event> = EventSerializer
) {
    companion object {
        val DEFAULT_JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    }
}

internal class EventsTable(serialization: EventSerialization) : UUIDTable("outbox_events") {
    val event = jsonb("event", serialization.json, serialization.serializer)
    val bucket = integer("bucket")
    val traceInformation =
        jsonb("trace_information", EventSerialization.DEFAULT_JSON, TraceInformation.serializer()).nullable()
    val publishedAt = datetime("published_at").nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}

internal class EventSourcingTable(serialization: EventSerialization) : UUIDTable("event_sourcing") {
    val rootId = text("root_id")
    val event = jsonb("data", serialization.json, serialization.serializer)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}
