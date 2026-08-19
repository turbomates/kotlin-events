package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import com.turbomates.event.NoOpTelemetry
import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The outbox of an application: how many buckets its rows are written with, how many of them one
 * sweep takes, how the events are serialized, how a bucket is locked, and the interceptor that
 * writes the rows. Build it once at startup and hand it to both the interceptor and the
 * [OutboxPublisher], they have to agree on all of it.
 *
 * ```
 * val outbox = Outbox(bucketCount = 16, events = EventRegistry.discovered())
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
 * @param events the events of the application and how they are written, see [EventRegistry]. Pass
 * the registry the rest of the application was built with — the same instance the rabbit publisher,
 * the consumer and the event sourcing storage hold, or the rows of one are unreadable by the other.
 * Required without a default for the same reason [bucketCount] is: it describes the rows already
 * written, and a defaulted empty registry would be a wiring mistake nothing points at.
 * @param retryPolicy backoff of an event the publishers keep failing, see [OutboxRetryPolicy].
 */
class Outbox(
    private val bucketCount: Int,
    private val events: EventRegistry,
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    private val bucketLock: OutboxBucketLock = PostgresAdvisoryBucketLock(),
    private val retryPolicy: OutboxRetryPolicy = OutboxRetryPolicy(),
    private val telemetryService: Telemetry = NoOpTelemetry(),
) {
    init {
        require(bucketCount in 1..MAX_BUCKET_COUNT) {
            "outbox bucket count must be in 1..$MAX_BUCKET_COUNT, got $bucketCount"
        }
        require(batchLimit > 0) { "outbox batch limit must be positive, got $batchLimit" }
    }

    /** Where the next sweep starts, so workers polling in lockstep do not fight over one bucket. */
    private var sweepStart = Random.nextInt(bucketCount)

    internal val eventsTable = EventsTable(events)
    internal val sourcingTable = EventSourcingTable(events)

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

    fun traceInformation() = telemetryService.traceInformation()

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

    /**
     * The unpublished head of [bucket], up to `batchLimit` events in the order they were written,
     * without the streams whose head waits out a backoff: nothing overtakes a failed event of its
     * own partition, the other partitions of the bucket are unaffected. A row that can no longer be
     * decoded is returned with its [OutboxBatch.Item.error] instead of failing the whole bucket.
     * Call it inside the transaction that holds the bucket.
     */
    fun load(bucket: Int): OutboxBatch {
        val blocked = eventsTable
            .select(eventsTable.partitionKey)
            .withDistinct()
            .where {
                (eventsTable.bucket eq bucket) and eventsTable.publishedAt.isNull() and
                        (eventsTable.nextAttemptAt greater CurrentDateTime)
            }
            .map { it[eventsTable.partitionKey] }
        val rawEvent = eventsTable.event.castTo<String>(TextColumnType())
        val rows = eventsTable
            .select(
                eventsTable.id,
                rawEvent,
                eventsTable.partitionKey,
                eventsTable.attempts,
                eventsTable.traceInformation,
                eventsTable.createdAt
            )
            .where {
                val head = (eventsTable.bucket eq bucket) and eventsTable.publishedAt.isNull()
                if (blocked.isEmpty()) head else head and (eventsTable.partitionKey notInList blocked)
            }
            .orderBy(eventsTable.sequence to SortOrder.ASC)
            .limit(batchLimit)
            .toList()
        return OutboxBatch(
            rows.map { row ->
                val decoded =
                    runCatching { events.json.decodeFromString(events.serializer, row[rawEvent]) }
                OutboxBatch.Item(
                    id = row[eventsTable.id].value,
                    partitionKey = row[eventsTable.partitionKey],
                    attempts = row[eventsTable.attempts],
                    event = decoded.getOrNull(),
                    payload = row[rawEvent],
                    traceInformation = row[eventsTable.traceInformation],
                    error = decoded.exceptionOrNull()
                )
            },
            rows.firstOrNull()?.get(eventsTable.createdAt)
        )
    }

    /**
     * Counts a failed publish of a row: one more attempt made, the next one no earlier than the
     * backoff of the retry policy from now — database time, the clocks of the pods stay out of it.
     * Call it in a transaction of its own, after the publishing transaction rolled back and while
     * the bucket is still held: until the delay runs out, [load] keeps the whole stream of the row
     * back, so its events are not published out of order.
     *
     * @param attempts the value the row was loaded with, see [OutboxBatch.Item.attempts].
     * @return false when the row is already gone.
     */
    fun failed(id: UUID, attempts: Int): Boolean {
        val made = attempts + 1
        return eventsTable.update({ eventsTable.id eq id }) {
            it[eventsTable.attempts] = made
            it[eventsTable.nextAttemptAt] = NextAttemptAt(retryPolicy.delay(made))
        } > 0
    }

    /**
     * Removes a row of the outbox. Call it inside the transaction that publishes the event: a
     * publisher that throws rolls the deletion back and leaves the event for the next sweep.
     *
     * @return false when the row is already gone, published by someone else.
     */
    fun delete(id: UUID): Boolean = eventsTable.deleteWhere { eventsTable.id eq id } > 0

    /**
     * Unpublished rows of the whole outbox, the backlog the workers are draining. Published rows
     * are deleted, so this counts a table that stays small in a healthy system — microseconds; a
     * deep backlog of hundreds of thousands of rows counts in low tens of milliseconds, once per
     * sweep. Call it inside a transaction.
     */
    fun depth(): Long = eventsTable.selectAll().where { eventsTable.publishedAt.isNull() }.count()

    /**
     * Writes [raised] to the outbox, and the ones that are event sourced to the event sourcing table.
     * Call it inside the transaction that raised them, which is what makes the events atomic with the
     * business data, [OutboxInterceptor] does it on commit.
     *
     * An event whose declared name the registry of this outbox does not hold is refused before a row
     * is written. The sweep of the outbox reads its own rows back, so an unreadable row is not a
     * failure that may pass: it would be carried until it is deleted by hand, holding back the whole
     * stream it belongs to, and an event sourced aggregate with one in its history would never be
     * rebuilt again. Refusing here fails the transaction that raised the event, at its author,
     * instead of surfacing days later as a stream that stopped moving.
     */
    fun batchEventsInsert(raised: List<PublicEvent>) {
        raised.forEach { event ->
            check(events.readable(event.original.key)) {
                "Event '${event.original.key.name}' is not registered in the EventRegistry of this " +
                    "outbox: its row could not be read back by the sweep and would never be " +
                    "published. Pass its key to the registry the outbox was built with."
            }
        }
        eventsTable.batchInsert(raised) { event ->
            this[eventsTable.id] = event.id
            this[eventsTable.event] = event.original
            this[eventsTable.bucket] = bucket(event.original.partitionKey, event.id)
            this[eventsTable.partitionKey] = event.original.partitionKey ?: event.id
            this[eventsTable.createdAt] = event.createdAt
            this[eventsTable.traceInformation] = event.traceInformation
        }
        sourcingTable.batchInsert(raised.mapNotNull { it.original as? EventSourcingEvent }) { event ->
            this[sourcingTable.id] = uuidV7()
            this[sourcingTable.rootId] = event.rootId
            this[sourcingTable.event] = event
            this[sourcingTable.createdAt] = event.timestamp
        }
    }

    class OutboxBatch(val events: List<Item>, val oldestCreatedAt: LocalDateTime?) {
        fun lag(): Duration {
            val oldest = oldestCreatedAt ?: return Duration.ZERO
            return java.time.Duration.between(oldest, LocalDateTime.now(ZoneOffset.UTC))
                .toKotlinDuration()
                .coerceAtLeast(Duration.ZERO)
        }

        /**
         * One unpublished row. [event] is null when the row can not be read back — an unknown type
         * mid-rollout, a serializer that lost a field — with the cause in [error]; the publisher
         * treats it as one more failed attempt, not as a failure of the batch. [payload] is the row
         * as it is stored, which is all there is of an event that can not be decoded.
         */
        class Item(
            val id: UUID,
            val partitionKey: UUID,
            val attempts: Int,
            val event: Event?,
            val payload: String,
            val traceInformation: TraceInformation?,
            val error: Throwable? = null
        )
    }

    companion object {
        const val MAX_BUCKET_COUNT: Int = 256

        /** Per bucket, a sweep publishes up to this many events times the bucket count. */
        const val DEFAULT_BATCH_LIMIT: Int = 100
    }
}

internal class EventsTable(events: EventRegistry) : UUIDTable("outbox_events") {
    val event = jsonb("event", events.json, events.serializer)
    val bucket = integer("bucket")

    /** The stream of the row: the partition key of the event, its own id when it has none. */
    val partitionKey = javaUUID("partition_key")

    /** Publish order, assigned by the database: client timestamps are neither unique nor monotonic. */
    val sequence = long("sequence").databaseGenerated()
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = datetime("next_attempt_at").nullable()
    val traceInformation =
        jsonb("trace_information", EventRegistry.DEFAULT_JSON, TraceInformation.serializer()).nullable()
    val publishedAt = datetime("published_at").nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}

/** `now() + delay` of the database, so the backoff does not depend on the clock of a pod. */
private class NextAttemptAt(private val delay: kotlin.time.Duration) : Expression<LocalDateTime?>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder { +"(now() + ${delay.inWholeMilliseconds} * interval '1 millisecond')" }
    }
}

internal class EventSourcingTable(events: EventRegistry) : UUIDTable("event_sourcing") {
    val rootId = text("root_id")
    val event = jsonb("data", events.json, events.serializer)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}
