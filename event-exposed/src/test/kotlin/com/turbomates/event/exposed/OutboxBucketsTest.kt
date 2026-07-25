package com.turbomates.event.exposed

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OutboxBucketsTest {
    @Test
    fun `partition key defines the bucket`() {
        val partitionKey = UUID.randomUUID()
        val first = PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey))
        val second = PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey))

        assertNotEquals(first.id, second.id)
        assertEquals(OutboxBuckets.of(partitionKey), first.bucket)
        assertEquals(first.bucket, second.bucket)
    }

    @Test
    fun `event without a partition key falls back to its own id`() {
        val event = PublicEvent(OutboxEvent(UUID.randomUUID()))

        assertEquals(OutboxBuckets.of(event.id), event.bucket)
    }

    @Test
    fun `buckets stay in range and spread the keys`() {
        val buckets = (1..2000).map { OutboxBuckets.of(UUID.randomUUID()) }

        assertTrue(buckets.all { it in 0 until OutboxBuckets.COUNT })
        assertEquals(OutboxBuckets.COUNT, buckets.distinct().size)
    }

    @Test
    fun `bucket is the documented formula`() {
        val key = UUID.fromString("00000000-0000-0000-0000-00000000002a")

        assertEquals(
            Math.floorMod(key.mostSignificantBits xor key.leastSignificantBits, OutboxBuckets.COUNT),
            OutboxBuckets.of(key)
        )
    }
}
