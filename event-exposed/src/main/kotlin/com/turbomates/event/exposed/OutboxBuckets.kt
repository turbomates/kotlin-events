package com.turbomates.event.exposed

import java.util.UUID

/**
 * Bucket layout of the outbox table.
 *
 * The count is given by the application, once, through [OutboxPublisher]. It describes the rows
 * already written to `outbox_events`, not a deployment: changing it re-maps every partition key, so
 * events of one stream would sit in two buckets at once and could be published by two workers in
 * parallel. Changing it means draining the outbox first, it is not something to turn in a config.
 *
 * A process that writes events without publishing them has to call [configure] itself at startup.
 */
object OutboxBuckets {
    const val MAX_COUNT: Int = 256

    @Volatile
    private var configured: Int = 0

    /** @throws OutboxBucketsNotConfiguredException when the application never configured the count. */
    val count: Int
        get() = configured.takeIf { it > 0 } ?: throw OutboxBucketsNotConfiguredException()

    /**
     * Fixes the bucket count of this process. Calling it again with the same count changes nothing.
     *
     * @throws OutboxBucketCountMismatchException when the process is already writing with another count.
     */
    fun configure(count: Int) {
        require(count in 1..MAX_COUNT) { "outbox bucket count must be in 1..$MAX_COUNT, got $count" }
        val current = configured
        if (current > 0 && current != count) {
            throw OutboxBucketCountMismatchException(current, count)
        }
        configured = count
    }

    /** Bucket of an outbox row: the event partition key when it has one, its own id otherwise. */
    fun of(partitionKey: UUID?, eventId: UUID): Int = of(partitionKey ?: eventId)

    fun of(key: UUID): Int = of(key, count)

    fun of(key: UUID, count: Int): Int =
        Math.floorMod(key.mostSignificantBits xor key.leastSignificantBits, count)
}

class OutboxBucketsNotConfiguredException : IllegalStateException(
    "Outbox bucket count is not configured. Build the OutboxPublisher before writing events, or " +
        "call OutboxBuckets.configure() at startup when this process only writes them."
)

class OutboxBucketCountMismatchException(val configured: Int, val requested: Int) : IllegalStateException(
    "Outbox bucket count is already $configured, refusing to switch to $requested. " +
        "Re-bucketing splits a partition across two buckets, drain outbox_events before changing the count."
)
