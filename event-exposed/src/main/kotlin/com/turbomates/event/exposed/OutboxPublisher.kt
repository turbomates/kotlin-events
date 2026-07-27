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
 * do not keep fighting over the same bucket. Every transaction takes the bucket with a non blocking
 * lock and releases it on commit, buckets held by another worker are skipped. Events of one partition
 * key always share a bucket, so they are never published by two workers at the same time.
 *
 * An event is published in its own transaction, which deletes the row first and commits after the
 * publishers are done: a batch is not a unit of work, so a failure on the hundredth event costs one
 * redelivery, not ninety nine. A failed event stays in the table for the next sweep while the rest of
 * the batch continues, out of order with its partition.
 *
 * @param bucketCount buckets the outbox is written with. It describes the rows already in
 * `outbox_events`, changing it for a non empty outbox re-maps every partition key, and every process
 * of the application has to be given the same count.
 * @param batchSize events read per bucket, not per sweep: a sweep may publish up to
 * `batchSize * bucketCount` events.
 */
class OutboxPublisher(
    private val database: Database,
    private val publishers: List<Publisher>,
    private val bucketCount: Int,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val delay: Duration = Duration.parse("1s"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bucketLock: OutboxBucketLock = PostgresAdvisoryBucketLock(),
    private val metrics: OutboxMetrics = LoggingOutboxMetrics()
) : CoroutineScope by CoroutineScope(dispatcher) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var sweepStart: Int

    init {
        OutboxBuckets.configure(bucketCount)
        sweepStart = Random.nextInt(bucketCount)
    }

    fun start(): Job {
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
        sweepStart = (start + 1) % bucketCount
        var owned = 0
        for (offset in 0 until bucketCount) {
            val bucket = (start + offset) % bucketCount
            try {
                if (publish(bucket)) {
                    owned++
                }
            } catch (ignore: Throwable) {
                metrics.bucketFailed(bucket, ignore)
                logger.error("error while publishing events of bucket $bucket", ignore)
            }
        }
        metrics.sweepCompleted(owned, bucketCount)
    }

    /** @return true when this worker owned the bucket, false when it is held by another one. */
    private suspend fun publish(bucket: Int): Boolean {
        val batch = claim(bucket)
        if (batch == null) {
            metrics.bucketSkipped(bucket)
            return false
        }
        metrics.bucketAcquired(bucket, batch.events.size, batch.lag())
        var published = 0
        var failed = 0
        for (event in batch.events) {
            val result = try {
                publish(bucket, event)
            } catch (ignore: Throwable) {
                failed++
                logger.error("error while publishing event ${event.id}", ignore)
                Published.FAILED
            }
            if (result == Published.BUCKET_TAKEN) {
                metrics.bucketSkipped(bucket)
                break
            }
            if (result == Published.DONE) {
                published++
            }
        }
        metrics.bucketPublished(bucket, published, failed)
        return true
    }

    /** Locks the bucket and reads its batch. @return null when another worker holds the bucket. */
    private suspend fun claim(bucket: Int): OutboxBatch? = suspendTransaction(database) {
        if (bucketLock.tryLock(this, bucket)) load(bucket, batchSize) else null
    }

    /**
     * Deletes the row and publishes it in the same transaction: the publishers run against a row that
     * no other worker can take, and a publisher that throws rolls the deletion back, leaving the event
     * for the next sweep.
     */
    private suspend fun publish(bucket: Int, event: PublicEvent): Published = suspendTransaction(database) {
        if (!bucketLock.tryLock(this, bucket)) {
            return@suspendTransaction Published.BUCKET_TAKEN
        }
        if (EventsTable.deleteWhere { EventsTable.id eq event.id } == 0) {
            return@suspendTransaction Published.GONE
        }
        publishers.forEach { publisher ->
            logger.debug(
                "event " + event.id.toString() +
                    " was published by ${publisher.javaClass.name} in worker"
            )
            publisher.publish(event.original, event.traceInformation)
        }
        Published.DONE
    }

    private enum class Published { DONE, FAILED, GONE, BUCKET_TAKEN }

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
        /** Per bucket, a sweep publishes up to this many events times the bucket count. */
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
