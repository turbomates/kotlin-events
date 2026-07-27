package com.turbomates.event.exposed

import com.turbomates.event.Publisher
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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

/**
 * Publishes the outbox in buckets instead of scanning the whole table.
 *
 * A sweep walks every bucket once, starting at a rotating position so workers that poll in lockstep
 * do not keep fighting over the same bucket. A bucket is taken with a non blocking lock and held for
 * the whole batch, buckets held by another worker are skipped. Events of one partition key always
 * share a bucket, so they are never published by two workers at the same time.
 *
 * The transaction that holds the lock does nothing but hold it, every event is published in a
 * transaction of its own which deletes the row first and commits after the publishers are done: a
 * batch is not a unit of work, so a failure on the hundredth event costs one redelivery, not ninety
 * nine. A failed event stays in the table for the next sweep while the rest of the batch continues,
 * out of order with its partition. A worker therefore uses two connections while it publishes a
 * bucket, the pool has to have room for them.
 *
 * @param outbox the same outbox the interceptor of this application writes to, it carries the bucket
 * count and the serialization of the rows.
 * @param batchSize events read per bucket, not per sweep: a sweep may publish up to
 * `batchSize * bucketCount` events.
 */
class OutboxPublisher(
    private val database: Database,
    private val publishers: List<Publisher>,
    private val outbox: Outbox,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val delay: Duration = Duration.parse("1s"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bucketLock: OutboxBucketLock = PostgresAdvisoryBucketLock(),
    private val metrics: OutboxMetrics = NoOpOutboxMetrics
) : CoroutineScope by CoroutineScope(dispatcher) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val table = outbox.events
    private val bucketCount = outbox.bucketCount
    private var sweepStart = Random.nextInt(outbox.bucketCount)

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

    /**
     * Holds the bucket for the whole batch: this transaction takes the lock, reads the batch and then
     * only waits, the events are published in transactions of their own, so nothing that happens to
     * one of them can undo the others. The lock lives until this transaction ends.
     *
     * @return true when this worker owned the bucket, false when it is held by another one.
     */
    private suspend fun publish(bucket: Int): Boolean = suspendTransaction(database) {
        if (!bucketLock.tryLock(this, bucket)) {
            metrics.bucketSkipped(bucket)
            return@suspendTransaction false
        }
        val batch = load(bucket, batchSize)
        metrics.bucketAcquired(bucket, batch.events.size, batch.lag())
        var published = 0
        var failed = 0
        for (event in batch.events) {
            try {
                if (publish(event)) {
                    published++
                }
            } catch (ignore: Throwable) {
                failed++
                logger.error("error while publishing event ${event.id}", ignore)
            }
        }
        metrics.bucketPublished(bucket, published, failed)
        true
    }

    /**
     * Publishes one event in a transaction of its own, which deletes the row before the publishers
     * run: a publisher that throws rolls that deletion back and leaves the event for the next sweep,
     * and the row lock keeps a worker that lost the bucket from publishing the same event twice.
     *
     * @return false when the row is already gone.
     */
    private suspend fun publish(event: PublicEvent): Boolean =
        inTopLevelSuspendTransaction(database, null, null, null) {
            if (table.deleteWhere { table.id eq event.id } == 0) {
                return@inTopLevelSuspendTransaction false
            }
            publishers.forEach { publisher ->
                logger.debug(
                    "event " + event.id.toString() +
                        " was published by ${publisher.javaClass.name} in worker"
                )
                publisher.publish(event.original, event.traceInformation)
            }
            true
        }

    private fun JdbcTransaction.load(bucket: Int, limit: Int): OutboxBatch {
        val rows = table
            .selectAll()
            .where { (table.bucket eq bucket) and table.publishedAt.isNull() }
            .orderBy(table.createdAt to SortOrder.ASC, table.id to SortOrder.ASC)
            .limit(limit)
            .toList()
        return OutboxBatch(
            events = rows.map { PublicEvent(it[table.event], it[table.id].value, it[table.traceInformation]) },
            oldestCreatedAt = rows.firstOrNull()?.get(table.createdAt)
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
