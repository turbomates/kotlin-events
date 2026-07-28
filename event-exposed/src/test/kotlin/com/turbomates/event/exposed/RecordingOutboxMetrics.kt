package com.turbomates.event.exposed

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** Keeps what the publisher reported, so the tests can assert on the distribution. */
class RecordingOutboxMetrics : OutboxMetrics {
    private val lags = ConcurrentHashMap<Int, Duration>()
    private val pending = ConcurrentHashMap<Int, Int>()
    private val published = ConcurrentHashMap<Int, AtomicLong>()
    private val failed = ConcurrentHashMap<Int, AtomicLong>()
    private val skipped = ConcurrentHashMap<Int, AtomicLong>()
    private val acquired = ConcurrentHashMap.newKeySet<Int>()
    private val eventFailures = ConcurrentHashMap<UUID, Int>()
    private val sweeps = AtomicLong()

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

    override fun eventFailed(bucket: Int, eventId: UUID, attempts: Int, error: Throwable) {
        eventFailures[eventId] = attempts
    }

    override fun sweepCompleted(ownedBuckets: Int, totalBuckets: Int) {
        this.ownedBuckets = ownedBuckets
        this.totalBuckets = totalBuckets
        sweeps.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        ownedBuckets = ownedBuckets,
        totalBuckets = totalBuckets,
        acquiredBuckets = acquired.toSet(),
        lag = lags.toMap(),
        pending = pending.toMap(),
        published = published.mapValues { it.value.get() },
        failed = failed.mapValues { it.value.get() },
        skipped = skipped.mapValues { it.value.get() },
        eventFailures = eventFailures.toMap(),
        sweeps = sweeps.get()
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
        val skipped: Map<Int, Long>,
        val eventFailures: Map<UUID, Int>,
        val sweeps: Long
    ) {
        val maxLag: Duration get() = lag.values.maxOrNull() ?: Duration.ZERO
        val publishedTotal: Long get() = published.values.sum()
        val failedTotal: Long get() = failed.values.sum()
    }
}
