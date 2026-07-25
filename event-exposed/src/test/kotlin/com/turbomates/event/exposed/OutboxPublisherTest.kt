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
import kotlin.test.assertFailsWith
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
import org.jetbrains.exposed.v1.core.eq
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
        transaction(database) {
            exec("DROP TABLE IF EXISTS outbox_events")
            exec("DROP TABLE IF EXISTS outbox_settings")
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

        val job = OutboxPublisher(database, listOf(publisher), delay = POLL_DELAY).start()
        awaitUntil { publisher.published.isNotEmpty() }
        job.cancelAndJoin()

        assertEquals(1, publisher.published.size)
        assertEquals((event.original as OutboxEvent).id, publisher.published.first().id)
        assertEquals(0L, unpublished())
    }

    @Test
    fun `publishes every bucket`() = runBlocking {
        val events = (1..OutboxBuckets.COUNT * 4).map { PublicEvent(OutboxEvent(UUID.randomUUID())) }
        events.forEach { insert(it) }
        val publisher = CollectingPublisher()
        val metrics = InMemoryOutboxMetrics()

        val job = OutboxPublisher(database, listOf(publisher), delay = POLL_DELAY, metrics = metrics).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()

        assertEquals(events.size, publisher.published.size)
        val snapshot = metrics.snapshot()
        assertEquals(OutboxBuckets.COUNT, snapshot.ownedBuckets)
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
            val job = OutboxPublisher(database, listOf(publisher), delay = POLL_DELAY, metrics = metrics).start()
            awaitUntil { publisher.published.isNotEmpty() && metrics.snapshot().ownedBuckets > 0 }
            job.cancelAndJoin()

            assertEquals(listOf((free.original as OutboxEvent).id), publisher.published.map { it.id })
            assertEquals(1L, unpublished())
            val snapshot = metrics.snapshot()
            assertFalse(lockedBucket in snapshot.acquiredBuckets)
            assertTrue((snapshot.skipped[lockedBucket] ?: 0) > 0)
            assertEquals(OutboxBuckets.COUNT - 1, snapshot.ownedBuckets)
        }

        val job = OutboxPublisher(database, listOf(publisher), delay = POLL_DELAY).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()
        assertEquals(2, publisher.published.size)
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

    @Test
    fun `bucket count is stored on the first start`() = runBlocking {
        val job = OutboxPublisher(database, emptyList(), delay = POLL_DELAY).start()
        job.cancelAndJoin()

        assertEquals(OutboxBuckets.COUNT, storedBucketCount())
    }

    @Test
    fun `fails to start when the database was written with another bucket count`() {
        transaction(database) {
            exec("INSERT INTO outbox_settings (name, value) VALUES ('bucket_count', ${OutboxBuckets.COUNT + 1})")
        }

        val exception = assertFailsWith<OutboxBucketCountMismatchException> {
            OutboxPublisher(database, emptyList()).start()
        }
        assertEquals(OutboxBuckets.COUNT + 1, exception.stored)
        assertEquals(OutboxBuckets.COUNT, exception.expected)
    }

    @Test
    fun `fails to start without the outbox settings table`() {
        transaction(database) { exec("DROP TABLE outbox_settings") }

        assertFailsWith<OutboxSettingsUnavailableException> {
            OutboxPublisher(database, emptyList()).start()
        }
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

    private fun storedBucketCount(): Int = transaction(database) {
        OutboxSettingsTable
            .selectAll()
            .where { OutboxSettingsTable.settingName eq OutboxBuckets.BUCKET_COUNT_SETTING }
            .single()[OutboxSettingsTable.settingValue]
    }

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
