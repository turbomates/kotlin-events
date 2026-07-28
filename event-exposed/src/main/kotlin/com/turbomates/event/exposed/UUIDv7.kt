package com.turbomates.event.exposed

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * UUIDv7 (RFC 9562): millisecond timestamp in the high bits, random tail. Outbox and event
 * sourcing rows get ever growing keys, so the primary key index appends instead of splitting
 * pages all over the tree. Identity only: publish order comes from the `sequence` column, the
 * timestamp inside the id is never read back.
 */
internal object UUIDv7 {
    private const val VERSION = 0x7000L
    private const val RANDOM_A_BITS = 0x0FFFL
    private const val RANDOM_B_BITS = 0x3FFFFFFFFFFFFFFFL
    private const val TIMESTAMP_SHIFT = 16

    fun randomUUID(): UUID {
        val random = ThreadLocalRandom.current()
        val mostSignificant = (System.currentTimeMillis() shl TIMESTAMP_SHIFT) or
            VERSION or
            (random.nextLong() and RANDOM_A_BITS)
        val leastSignificant = (random.nextLong() and RANDOM_B_BITS) or Long.MIN_VALUE
        return UUID(mostSignificant, leastSignificant)
    }
}
