package com.turbomates.event.exposed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import org.slf4j.LoggerFactory

/**
 * Observability of the bucket sweep. Without it there is no way to tell whether the work is actually
 * spread over the workers: [bucketAcquired] and [sweepCompleted] show how many buckets a pod really
 * holds, [bucketAcquired] also reports the age of the oldest unpublished event of the bucket.
 */
interface OutboxMetrics {
    /**
     * The bucket was locked by this worker.
     *
     * @param pending events read in this batch, equal to the batch size when the bucket has more.
     * @param lag age of the oldest unpublished event of the bucket, [Duration.ZERO] when it is empty.
     */
    fun bucketAcquired(bucket: Int, pending: Int, lag: Duration) {}

    /** The bucket is held by another worker and was skipped without waiting. */
    fun bucketSkipped(bucket: Int) {}

    fun bucketPublished(bucket: Int, published: Int, failed: Int) {}

    fun bucketFailed(bucket: Int, error: Throwable) {}

    /** A full pass over every bucket is over, [ownedBuckets] of [totalBuckets] were locked by this worker. */
    fun sweepCompleted(ownedBuckets: Int, totalBuckets: Int) {}
}

object NoOpOutboxMetrics : OutboxMetrics

class LoggingOutboxMetrics : OutboxMetrics {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun bucketAcquired(bucket: Int, pending: Int, lag: Duration) {
        logger.debug("outbox bucket $bucket acquired, $pending events in batch, lag $lag")
    }

    override fun bucketSkipped(bucket: Int) {
        logger.debug("outbox bucket $bucket is held by another worker")
    }

    override fun bucketPublished(bucket: Int, published: Int, failed: Int) {
        if (failed > 0) {
            logger.warn("outbox bucket $bucket published $published events, $failed failed")
        } else {
            logger.debug("outbox bucket $bucket published $published events")
        }
    }

    override fun bucketFailed(bucket: Int, error: Throwable) {
        logger.error("error while publishing events of outbox bucket $bucket", error)
    }

    override fun sweepCompleted(ownedBuckets: Int, totalBuckets: Int) {
        logger.debug("outbox sweep completed, $ownedBuckets of $totalBuckets buckets owned by this worker")
    }
}

/**
 * Keeps the last reported state per bucket in memory, so an application can expose it through its own
 * metrics registry, and tests can assert on it.
 */
class InMemoryOutboxMetrics : OutboxMetrics {
    private val lags = ConcurrentHashMap<Int, Duration>()
    private val pending = ConcurrentHashMap<Int, Int>()
    private val published = ConcurrentHashMap<Int, AtomicLong>()
    private val failed = ConcurrentHashMap<Int, AtomicLong>()
    private val skipped = ConcurrentHashMap<Int, AtomicLong>()
    private val acquired = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var ownedBuckets: Int = 0

    @Volatile
    private var totalBuckets: Int = 0

    override fun bucketAcquired(bucket: Int, pending: Int, lag: Duration) {
        acquired.add(bucket)
        this.pending[bucket] = pending
        lags[bucket] = lag
    }

    override fun bucketSkipped(bucket: Int) {
        skipped.counter(bucket).incrementAndGet()
    }

    override fun bucketPublished(bucket: Int, published: Int, failed: Int) {
        this.published.counter(bucket).addAndGet(published.toLong())
        this.failed.counter(bucket).addAndGet(failed.toLong())
    }

    override fun bucketFailed(bucket: Int, error: Throwable) {
        failed.counter(bucket).incrementAndGet()
    }

    override fun sweepCompleted(ownedBuckets: Int, totalBuckets: Int) {
        this.ownedBuckets = ownedBuckets
        this.totalBuckets = totalBuckets
    }

    fun snapshot(): Snapshot = Snapshot(
        ownedBuckets = ownedBuckets,
        totalBuckets = totalBuckets,
        acquiredBuckets = acquired.toSet(),
        lag = lags.toMap(),
        pending = pending.toMap(),
        published = published.mapValues { it.value.get() },
        failed = failed.mapValues { it.value.get() },
        skipped = skipped.mapValues { it.value.get() }
    )

    private fun ConcurrentHashMap<Int, AtomicLong>.counter(bucket: Int): AtomicLong =
        computeIfAbsent(bucket) { AtomicLong() }

    data class Snapshot(
        val ownedBuckets: Int,
        val totalBuckets: Int,
        val acquiredBuckets: Set<Int>,
        val lag: Map<Int, Duration>,
        val pending: Map<Int, Int>,
        val published: Map<Int, Long>,
        val failed: Map<Int, Long>,
        val skipped: Map<Int, Long>
    ) {
        val maxLag: Duration get() = lag.values.maxOrNull() ?: Duration.ZERO
        val publishedTotal: Long get() = published.values.sum()
        val failedTotal: Long get() = failed.values.sum()
    }
}
