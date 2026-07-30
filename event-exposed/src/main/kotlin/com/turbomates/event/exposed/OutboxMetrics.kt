package com.turbomates.event.exposed

import java.util.UUID
import kotlin.time.Duration

/**
 * Observability of the bucket sweep, for the application to wire into its own metrics registry.
 * Without it there is no way to tell whether the work is actually spread over the workers:
 * [bucketAcquired] and [sweepCompleted] show how many buckets a worker really holds, [bucketAcquired]
 * also reports the age of the oldest unpublished event of the bucket.
 *
 * Every method has an empty default, so an implementation only overrides what it exports. The
 * publisher logs its own errors, this interface is not a logging hook.
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

    /**
     * A publish of one event failed — a publisher threw, or the row can not be decoded — attempt
     * [attempts] was counted and the stream of the event waits out its backoff. This is the signal
     * a poison event alert is built on: [attempts] keeps growing while nobody looks.
     */
    fun eventFailed(bucket: Int, eventId: UUID, attempts: Int, error: Throwable) {}

    fun bucketFailed(bucket: Int, error: Throwable) {}

    /** A full pass over every bucket is over, [ownedBuckets] of [totalBuckets] were locked by this worker. */
    fun sweepCompleted(ownedBuckets: Int, totalBuckets: Int) {}

    /**
     * Unpublished events in the whole outbox, measured on a ticker of its own (`depthInterval` of
     * the publisher), so the gauge stays fresh even while a sweep drowns in a deep backlog. Its
     * slope is the rate the workers drain the backlog at, a growing value means the writers are
     * ahead of them. Every worker reports the same global number.
     */
    fun outboxDepth(events: Long) {}
}

object NoOpOutboxMetrics : OutboxMetrics
