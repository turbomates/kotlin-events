package com.turbomates.event.exposed

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal const val TEST_BUCKET_COUNT = 16

class OutboxTest {
    private val outbox = Outbox(TEST_BUCKET_COUNT)

    @Test
    fun `partition key defines the bucket`() {
        val partitionKey = UUID.randomUUID()
        val first = PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey))
        val second = PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey))

        assertNotEquals(first.id, second.id)
        assertEquals(outbox.bucket(partitionKey), outbox.bucket(first))
        assertEquals(outbox.bucket(first), outbox.bucket(second))
    }

    @Test
    fun `event without a partition key falls back to its own id`() {
        val event = PublicEvent(OutboxEvent(UUID.randomUUID()))

        assertEquals(outbox.bucket(event.id), outbox.bucket(event))
    }

    @Test
    fun `buckets stay in range and spread the keys`() {
        val buckets = (1..2000).map { outbox.bucket(UUID.randomUUID()) }

        assertTrue(buckets.all { it in 0 until TEST_BUCKET_COUNT })
        assertEquals(TEST_BUCKET_COUNT, buckets.distinct().size)
    }

    @Test
    fun `bucket is the documented formula`() {
        val key = UUID.fromString("00000000-0000-0000-0000-00000000002a")

        assertEquals(
            Math.floorMod(key.mostSignificantBits xor key.leastSignificantBits, TEST_BUCKET_COUNT),
            outbox.bucket(key)
        )
    }

    @Test
    fun `another bucket count is another layout`() {
        val wider = Outbox(TEST_BUCKET_COUNT * 2)
        val keys = (1..100).map { UUID.randomUUID() }

        assertTrue(
            keys.any { outbox.bucket(it) != wider.bucket(it) },
            "a bucket count of its own re-maps the partition keys"
        )
    }

    @Test
    fun `rejects a count out of range`() {
        assertFailsWith<IllegalArgumentException> { Outbox(0) }
        assertFailsWith<IllegalArgumentException> { Outbox(Outbox.MAX_BUCKET_COUNT + 1) }
    }

    @Test
    fun `refuses a second interceptor`() {
        val installed = outbox.install()
        try {
            assertFailsWith<IllegalStateException> { Outbox(TEST_BUCKET_COUNT).install() }
        } finally {
            org.jetbrains.exposed.v1.jdbc.JdbcTransaction.globalInterceptors.remove(installed)
        }
    }
}

private fun Outbox.bucket(event: PublicEvent): Int = bucket(event.original.partitionKey, event.id)
