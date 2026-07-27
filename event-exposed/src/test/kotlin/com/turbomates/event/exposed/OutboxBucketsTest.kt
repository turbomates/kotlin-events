package com.turbomates.event.exposed

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach

internal const val TEST_BUCKET_COUNT = 16

class OutboxBucketsTest {
    @BeforeEach
    fun configure() {
        OutboxBuckets.configure(TEST_BUCKET_COUNT)
    }

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

        assertTrue(buckets.all { it in 0 until TEST_BUCKET_COUNT })
        assertEquals(TEST_BUCKET_COUNT, buckets.distinct().size)
    }

    @Test
    fun `bucket is the documented formula`() {
        val key = UUID.fromString("00000000-0000-0000-0000-00000000002a")

        assertEquals(
            Math.floorMod(key.mostSignificantBits xor key.leastSignificantBits, TEST_BUCKET_COUNT),
            OutboxBuckets.of(key)
        )
    }

    @Test
    fun `refuses to write with a second bucket count`() {
        val exception = assertFailsWith<OutboxBucketCountMismatchException> {
            OutboxBuckets.configure(TEST_BUCKET_COUNT * 2)
        }

        assertEquals(TEST_BUCKET_COUNT, exception.configured)
        assertEquals(TEST_BUCKET_COUNT * 2, exception.requested)
        assertEquals(TEST_BUCKET_COUNT, OutboxBuckets.count)
    }

    @Test
    fun `rejects a count out of range`() {
        assertFailsWith<IllegalArgumentException> { OutboxBuckets.configure(0) }
        assertFailsWith<IllegalArgumentException> { OutboxBuckets.configure(OutboxBuckets.MAX_COUNT + 1) }
    }
}
