package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.Publisher
import com.turbomates.event.TraceInformation
import com.turbomates.event.exposed.serializer.UUIDSerializer
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class OutboxPublisherTest {
    @BeforeEach
    fun resetSchema() {
        OutboxBuckets.configure(TEST_BUCKET_COUNT)
        transaction(database) {
            exec("DROP TABLE IF EXISTS outbox_events")
        }
        transaction(database) {
            schema().forEach { exec(it) }
        }
    }

    @Test
    fun `successfully publishing`() = runBlocking {
        val publisher = CollectingPublisher()
        val event = PublicEvent(OutboxEvent(UUID.randomUUID()))
        insert(event)

        val job = OutboxPublisher(database, listOf(publisher), TEST_BUCKET_COUNT, delay = POLL_DELAY).start()
        awaitUntil { publisher.published.isNotEmpty() }
        job.cancelAndJoin()

        assertEquals(1, publisher.published.size)
        assertEquals((event.original as OutboxEvent).id, publisher.published.first().id)
        assertEquals(0L, unpublished())
    }

    @Test
    fun `publishes every bucket`() = runBlocking {
        val events = (1..TEST_BUCKET_COUNT * 4).map { PublicEvent(OutboxEvent(UUID.randomUUID())) }
        events.forEach { insert(it) }
        val publisher = CollectingPublisher()
        val metrics = InMemoryOutboxMetrics()

        val job = OutboxPublisher(database, listOf(publisher), TEST_BUCKET_COUNT, delay = POLL_DELAY, metrics = metrics).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()

        assertEquals(events.size, publisher.published.size)
        val snapshot = metrics.snapshot()
        assertEquals(TEST_BUCKET_COUNT, snapshot.ownedBuckets)
        assertEquals(events.size.toLong(), snapshot.publishedTotal)
        assertEquals(0L, snapshot.failedTotal)
        assertTrue(snapshot.maxLag > Duration.ZERO, "expected a non zero lag, got ${snapshot.maxLag}")
        assertTrue(snapshot.acquiredBuckets.containsAll(events.map { it.bucket }))
    }

    @Test
    fun `bucket held by another worker is skipped`() = runBlocking {
        val locked = PublicEvent(OutboxEvent(UUID.randomUUID()))
        val lockedBucket = locked.bucket
        val free = generateSequence { PublicEvent(OutboxEvent(UUID.randomUUID())) }
            .first { it.bucket != lockedBucket }
        insert(locked)
        insert(free)
        val publisher = CollectingPublisher()
        val metrics = InMemoryOutboxMetrics()

        holdBucket(lockedBucket).use {
            val job = OutboxPublisher(database, listOf(publisher), TEST_BUCKET_COUNT, delay = POLL_DELAY, metrics = metrics).start()
            awaitUntil { publisher.published.isNotEmpty() && metrics.snapshot().ownedBuckets > 0 }
            job.cancelAndJoin()

            assertEquals(listOf((free.original as OutboxEvent).id), publisher.published.map { it.id })
            assertEquals(1L, unpublished())
            val snapshot = metrics.snapshot()
            assertFalse(lockedBucket in snapshot.acquiredBuckets)
            assertTrue((snapshot.skipped[lockedBucket] ?: 0) > 0)
            assertEquals(TEST_BUCKET_COUNT - 1, snapshot.ownedBuckets)
        }

        val job = OutboxPublisher(database, listOf(publisher), TEST_BUCKET_COUNT, delay = POLL_DELAY).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()
        assertEquals(2, publisher.published.size)
    }

    @Test
    fun `a failing event does not republish the rest of its batch`() = runBlocking {
        val partitionKey = UUID.randomUUID()
        val events = (1..5).map { PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey)) }
        events.forEach { insert(it) }
        val failing = (events[2].original as PartitionedOutboxEvent).id
        val publisher = FailingPublisher(failing)

        val job = OutboxPublisher(database, listOf(publisher), TEST_BUCKET_COUNT, delay = POLL_DELAY).start()
        awaitUntil { unpublished() == 1L && publisher.attempts.count { it == failing } > 2 }
        job.cancelAndJoin()

        val delivered = publisher.published
        assertEquals(4, delivered.size, "every event but the failing one is published exactly once")
        assertEquals(delivered.toSet().size, delivered.size)
        assertFalse(failing in delivered)
        assertEquals(1L, unpublished())
    }

    @Test
    fun `events of one partition key are written to one bucket`() {
        val partitionKey = UUID.randomUUID()
        transaction(database) {
            events.addEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey))
            events.addEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey))
            events.addEvent(OutboxEvent(UUID.randomUUID()))
        }

        val rows = transaction(database) {
            EventsTable.selectAll().map { Triple(it[EventsTable.event], it[EventsTable.id].value, it[EventsTable.bucket]) }
        }

        assertEquals(3, rows.size)
        val partitioned = rows.filter { it.first is PartitionedOutboxEvent }
        assertEquals(2, partitioned.size)
        assertEquals(setOf(OutboxBuckets.of(partitionKey)), partitioned.map { it.third }.toSet())
        val unpartitioned = rows.single { it.first is OutboxEvent }
        assertEquals(OutboxBuckets.of(unpartitioned.second), unpartitioned.third)
    }

    private fun insert(event: PublicEvent) {
        transaction(database) {
            EventsTable.insert {
                it[EventsTable.id] = event.id
                it[EventsTable.event] = event.original
                it[EventsTable.bucket] = event.bucket
                it[EventsTable.createdAt] = event.createdAt
                it[EventsTable.traceInformation] = TraceInformation(null, null, null)
            }
        }
    }

    private fun unpublished(): Long = transaction(database) { EventsTable.selectAll().count() }

    /** Keeps the advisory lock of a bucket for as long as the returned connection stays open. */
    private fun holdBucket(bucket: Int): Connection {
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        connection.autoCommit = false
        connection.createStatement().use {
            it.execute(
                "SELECT pg_advisory_xact_lock(${PostgresAdvisoryBucketLock.DEFAULT_NAMESPACE}, $bucket)"
            )
        }
        return connection
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT) {
            while (!condition()) {
                delay(POLL_DELAY)
            }
        }
    }

    private fun schema(): List<String> {
        val sql = String(checkNotNull(javaClass.classLoader.getResourceAsStream(SCHEMA)).readAllBytes())
        return sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    class CollectingPublisher : Publisher {
        val published: MutableList<OutboxEvent> = Collections.synchronizedList(mutableListOf())
        override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
            published.add(event as OutboxEvent)
        }
    }

    /** Fails every time it is given [failing], collects everything else. */
    class FailingPublisher(private val failing: UUID) : Publisher {
        val attempts: MutableList<UUID> = Collections.synchronizedList(mutableListOf())
        val published: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

        override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
            val id = (event as PartitionedOutboxEvent).id
            attempts.add(id)
            check(id != failing) { "event $id can not be published" }
            published.add(id)
        }
    }

    companion object {
        private const val SCHEMA = "outbox_events_postgres_table.sql"
        private val POLL_DELAY = 50.milliseconds
        private val AWAIT_TIMEOUT = 30.seconds
        private lateinit var container: PostgreSQLContainer<*>
        private lateinit var database: Database

        @JvmStatic
        @BeforeAll
        fun startDatabase() {
            container = PostgreSQLContainer("postgres:14")
                .withEnv(mapOf("POSTGRES_USER" to "test", "POSTGRES_PASSWORD" to "test"))
            container.start()
            database = Database.connect(
                container.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = "test",
                password = "test"
            )
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() {
            container.stop()
        }
    }
}

@Serializable
data class OutboxEvent(@Serializable(with = UUIDSerializer::class) val id: UUID) : Event() {
    override val key: Key<out Event> get() = OutboxEvent

    companion object : Key<OutboxEvent>
}

@Serializable
data class PartitionedOutboxEvent(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @Serializable(with = UUIDSerializer::class) val userId: UUID
) : Event() {
    override val key: Key<out Event> get() = PartitionedOutboxEvent
    override val partitionKey get() = userId

    companion object : Key<PartitionedOutboxEvent>
}
