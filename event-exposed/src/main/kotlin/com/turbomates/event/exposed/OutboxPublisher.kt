package com.turbomates.event.exposed

import com.turbomates.event.Publisher
import com.turbomates.event.TraceInformation
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.json.jsonb
import org.slf4j.LoggerFactory

/**
 * Publishes the outbox in buckets instead of scanning the whole table.
 *
 * A sweep walks every bucket once, starting at a rotating position so workers that poll in lockstep
 * do not keep fighting over the same bucket. A bucket is taken with a non blocking lock inside the
 * batch transaction, buckets held by another worker are skipped, and the lock is released with the
 * transaction. Events of one partition key always share a bucket, so they are never published by two
 * workers at the same time. A batch is published in insertion order, an event that fails to publish
 * is left in the table for the next sweep while the rest of the batch continues.
 *
 * @param batchSize events read per bucket, not per sweep: a sweep may publish up to
 * `batchSize * OutboxBuckets.COUNT` events, and every batch is one transaction that stays open while
 * its events are published.
 */
class OutboxPublisher(
    private val database: Database,
    private val publishers: List<Publisher>,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val delay: Duration = Duration.parse("1s"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bucketLock: OutboxBucketLock = PostgresAdvisoryBucketLock(),
    private val metrics: OutboxMetrics = LoggingOutboxMetrics()
) : CoroutineScope by CoroutineScope(dispatcher) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var sweepStart = Random.nextInt(OutboxBuckets.COUNT)

    /**
     * @throws OutboxBucketCountMismatchException when the database was written with another bucket count.
     */
    fun start(): Job {
        OutboxBuckets.verify(database)
        return launch {
            while (isActive) {
                try {
                    sweep()
                } catch (ignore: Throwable) {
                    logger.error("error while publishing events", ignore)
                } finally {
                    delay(delay)
                }
            }
        }
    }

    private suspend fun sweep() {
        val start = sweepStart
        sweepStart = (start + 1) % OutboxBuckets.COUNT
        var owned = 0
        for (offset in 0 until OutboxBuckets.COUNT) {
            val bucket = (start + offset) % OutboxBuckets.COUNT
            try {
                if (publish(bucket)) {
                    owned++
                }
            } catch (ignore: Throwable) {
                metrics.bucketFailed(bucket, ignore)
                logger.error("error while publishing events of bucket $bucket", ignore)
            }
        }
        metrics.sweepCompleted(owned, OutboxBuckets.COUNT)
    }

    /** @return true when this worker owned the bucket, false when it is held by another one. */
    private suspend fun publish(bucket: Int): Boolean = suspendTransaction(database) {
        if (!bucketLock.tryLock(this, bucket)) {
            metrics.bucketSkipped(bucket)
            return@suspendTransaction false
        }
        val batch = load(bucket, batchSize)
        metrics.bucketAcquired(bucket, batch.events.size, batch.lag())
        var published = 0
        var failed = 0
        batch.events.forEach { event ->
            try {
                publishers.forEach { publisher ->
                    logger.debug(
                        "event " + event.id.toString() +
                            " was published by ${publisher.javaClass.name} in worker"
                    )
                    publisher.publish(event.original, event.traceInformation)
                }
                EventsTable.deleteWhere { EventsTable.id eq event.id }
                published++
            } catch (ignore: Throwable) {
                failed++
                logger.error("error while publishing event ${event.id}", ignore)
            }
        }
        metrics.bucketPublished(bucket, published, failed)
        true
    }

    private fun JdbcTransaction.load(bucket: Int, limit: Int): OutboxBatch {
        val rows = EventsTable
            .selectAll()
            .where { (EventsTable.bucket eq bucket) and EventsTable.publishedAt.isNull() }
            .orderBy(EventsTable.createdAt to SortOrder.ASC, EventsTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()
        return OutboxBatch(
            events = rows.map {
                PublicEvent(it[EventsTable.event], it[EventsTable.id].value, it[EventsTable.traceInformation])
            },
            oldestCreatedAt = rows.firstOrNull()?.get(EventsTable.createdAt)
        )
    }

    private class OutboxBatch(val events: List<PublicEvent>, val oldestCreatedAt: LocalDateTime?) {
        fun lag(): Duration {
            val oldest = oldestCreatedAt ?: return Duration.ZERO
            return java.time.Duration.between(oldest, LocalDateTime.now(ZoneOffset.UTC))
                .toKotlinDuration()
                .coerceAtLeast(Duration.ZERO)
        }
    }

    companion object {
        /** Per bucket, a sweep publishes up to this many events times [OutboxBuckets.COUNT]. */
        const val DEFAULT_BATCH_SIZE: Int = 100
    }
}

internal object EventsTable : UUIDTable("outbox_events") {
    val event =
        jsonb("event", Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }, EventSerializer)
    val bucket = integer("bucket")
    val traceInformation =
        jsonb(
            "trace_information", Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false },
            TraceInformation.serializer()
        ).nullable()
    val publishedAt = datetime("published_at").nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}
