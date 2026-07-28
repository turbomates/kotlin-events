package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.TraceInformation
import com.turbomates.event.exposed.EventSerialization.Companion.DEFAULT_JSON
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The outbox of an application: how many buckets its rows are written with, how many of them one
 * sweep takes, how the events are serialized, how a bucket is locked, and the interceptor that
 * writes the rows. Build it once at startup and hand it to both the interceptor and the
 * [OutboxPublisher], they have to agree on all of it.
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
 * @param batchLimit events read per bucket, not per sweep: a sweep may publish up to
 * `batchLimit * bucketCount` events. It only bounds what one worker takes from a bucket at a time,
 * the publishing transaction stays one event wide.
 * @param bucketLock how a bucket is held while it is published, by default a Postgres advisory lock.
 * @param serialization format of the `jsonb` columns holding the events.
 */
class Outbox(
    private val bucketCount: Int,
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    private val bucketLock: OutboxBucketLock = PostgresAdvisoryBucketLock(),
    private val serialization: EventSerialization = EventSerialization()
) {
    init {
        require(bucketCount in 1..MAX_BUCKET_COUNT) {
            "outbox bucket count must be in 1..$MAX_BUCKET_COUNT, got $bucketCount"
        }
        require(batchLimit > 0) { "outbox batch limit must be positive, got $batchLimit" }
    }

    /** Where the next sweep starts, so workers polling in lockstep do not fight over one bucket. */
    private var sweepStart = Random.nextInt(bucketCount)

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

    /**
     * Every bucket once, in the order the next sweep visits them. The call moves the outbox on: the
     * start rotates by one every time, so workers that poll in lockstep do not keep fighting over the
     * same bucket. One sweep, one call.
     */
    fun nextSweep(): List<Int> {
        val start = sweepStart
        sweepStart = (start + 1) % bucketCount
        return List(bucketCount) { offset -> (start + offset) % bucketCount }
    }

    /**
     * Takes the lock of [bucket] for [transaction], without waiting for a worker that holds it.
     *
     * @return true when the bucket was acquired, it stays held until [transaction] ends.
     */
    fun tryLock(transaction: JdbcTransaction, bucket: Int): Boolean {
        return bucketLock.tryLock(transaction, bucket)
    }

    /** The unpublished head of [bucket], up to `batchLimit` events. Call it inside a transaction. */
    fun load(bucket: Int): OutboxBatch {
        val rows = events
            .selectAll()
            .where { (events.bucket eq bucket) and events.publishedAt.isNull() }
            .orderBy(events.createdAt to SortOrder.ASC, events.id to SortOrder.ASC)
            .limit(batchLimit)
            .toList()
        return OutboxBatch(
            rows.map { PublicEvent(it[events.event], it[events.id].value, it[events.traceInformation]) },
            rows.firstOrNull()?.get(events.createdAt)
        )
    }

    /**
     * Removes a row of the outbox. Call it inside the transaction that publishes the event: a
     * publisher that throws rolls the deletion back and leaves the event for the next sweep.
     *
     * @return false when the row is already gone, published by someone else.
     */
    fun delete(id: UUID): Boolean = events.deleteWhere { events.id eq id } > 0

    /**
     * Writes [raised] to the outbox, and the ones that are event sourced to the event sourcing table.
     * Call it inside the transaction that raised them, which is what makes the events atomic with the
     * business data, [OutboxInterceptor] does it on commit.
     */
    fun batchEventsInsert(raised: List<PublicEvent>) {
        events.batchInsert(raised) { event ->
            this[events.id] = event.id
            this[events.event] = event.original
            this[events.bucket] = bucket(event.original.partitionKey, event.id)
            this[events.createdAt] = event.createdAt
            this[events.traceInformation] = event.traceInformation
        }
        eventSourcing.batchInsert(raised.mapNotNull { it.original as? EventSourcingEvent }) { event ->
            this[eventSourcing.id] = UUID.randomUUID()
            this[eventSourcing.rootId] = event.rootId
            this[eventSourcing.event] = event
            this[eventSourcing.createdAt] = event.timestamp
        }
    }

    class OutboxBatch(val events: List<PublicEvent>, val oldestCreatedAt: LocalDateTime?) {
        fun lag(): Duration {
            val oldest = oldestCreatedAt ?: return Duration.ZERO
            return java.time.Duration.between(oldest, LocalDateTime.now(ZoneOffset.UTC))
                .toKotlinDuration()
                .coerceAtLeast(Duration.ZERO)
        }
    }

    companion object {
        const val MAX_BUCKET_COUNT: Int = 256

        /** Per bucket, a sweep publishes up to this many events times the bucket count. */
        const val DEFAULT_BATCH_LIMIT: Int = 100
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
