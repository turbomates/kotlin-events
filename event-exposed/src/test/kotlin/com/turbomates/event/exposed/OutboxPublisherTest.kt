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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class OutboxPublisherTest {
    private val outbox = Outbox(TEST_BUCKET_COUNT)
    private lateinit var interceptor: OutboxInterceptor

    @BeforeEach
    fun resetSchema() {
        interceptor = outbox.install()
        transaction(database) {
            exec("DROP TABLE IF EXISTS outbox_events")
        }
        transaction(database) {
            statements(SCHEMA).forEach { exec(it) }
        }
    }

    @AfterEach
    fun removeInterceptor() {
        JdbcTransaction.globalInterceptors.remove(interceptor)
    }

    @Test
    fun `successfully publishing`() = runBlocking {
        val publisher = CollectingPublisher()
        val event = PublicEvent(OutboxEvent(UUID.randomUUID()))
        insert(event)

        val job = OutboxPublisher(database, listOf(publisher), outbox, delay = POLL_DELAY).start()
        awaitUntil { publisher.published.isNotEmpty() }
        job.cancelAndJoin()

        assertEquals(listOf((event.original as OutboxEvent).id), publisher.published.toList())
        assertEquals(0L, unpublished())
    }

    @Test
    fun `publishes every bucket`() = runBlocking {
        val events = (1..TEST_BUCKET_COUNT * 4).map { PublicEvent(OutboxEvent(UUID.randomUUID())) }
        events.forEach { insert(it) }
        val publisher = CollectingPublisher()
        val metrics = RecordingOutboxMetrics()

        val job = OutboxPublisher(
            database, listOf(publisher), outbox,
            delay = POLL_DELAY, depthInterval = POLL_DELAY, metrics = metrics
        ).start()
        awaitUntil {
            unpublished() == 0L && metrics.snapshot().let { it.sweeps > 0 && it.depth == 0L }
        }
        job.cancelAndJoin()

        assertEquals(events.size, publisher.published.size)
        val snapshot = metrics.snapshot()
        assertEquals(TEST_BUCKET_COUNT, snapshot.ownedBuckets)
        assertEquals(events.size.toLong(), snapshot.publishedTotal)
        assertEquals(0L, snapshot.failedTotal)
        assertTrue(snapshot.maxLag > Duration.ZERO, "expected a non zero lag, got ${snapshot.maxLag}")
        assertTrue(snapshot.acquiredBuckets.containsAll(events.map { outbox.bucket(it.original.partitionKey, it.id) }))
        assertEquals(0L, snapshot.depth, "the drained outbox reports an empty backlog")
    }

    @Test
    fun `bucket held by another worker is skipped`() = runBlocking {
        val locked = PublicEvent(OutboxEvent(UUID.randomUUID()))
        val lockedBucket = outbox.bucket(locked.original.partitionKey, locked.id)
        val free = generateSequence { PublicEvent(OutboxEvent(UUID.randomUUID())) }
            .first { outbox.bucket(it.original.partitionKey, it.id) != lockedBucket }
        insert(locked)
        insert(free)
        val publisher = CollectingPublisher()
        val metrics = RecordingOutboxMetrics()

        holdBucket(lockedBucket).use {
            val job = OutboxPublisher(database, listOf(publisher), outbox, delay = POLL_DELAY, metrics = metrics).start()
            awaitUntil { publisher.published.isNotEmpty() && metrics.snapshot().ownedBuckets > 0 }
            job.cancelAndJoin()

            assertEquals(listOf((free.original as OutboxEvent).id), publisher.published.toList())
            assertEquals(1L, unpublished())
            val snapshot = metrics.snapshot()
            assertFalse(lockedBucket in snapshot.acquiredBuckets)
            assertTrue((snapshot.skipped[lockedBucket] ?: 0) > 0)
            assertEquals(TEST_BUCKET_COUNT - 1, snapshot.ownedBuckets)
        }

        val job = OutboxPublisher(database, listOf(publisher), outbox, delay = POLL_DELAY).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()
        assertEquals(2, publisher.published.size)
    }

    @Test
    fun `a failing event blocks its stream and the other streams continue`() = runBlocking {
        val partitionKey = UUID.randomUUID()
        val stream = (1..3).map { PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey)) }
        val other = PublicEvent(OutboxEvent(UUID.randomUUID()))
        stream.forEach { insert(it) }
        insert(other)
        val head = stream.first()
        val publisher = FailingPublisher(head.original.testId())
        val metrics = RecordingOutboxMetrics()
        val retrying = Outbox(TEST_BUCKET_COUNT, retryPolicy = OutboxRetryPolicy(initialDelay = 1.hours))

        val job = OutboxPublisher(
            database, listOf(publisher), retrying,
            delay = POLL_DELAY, depthInterval = POLL_DELAY, metrics = metrics
        ).start()
        awaitUntil {
            val snapshot = metrics.snapshot()
            (other.original as OutboxEvent).id in publisher.published &&
                snapshot.eventFailures.isNotEmpty() && snapshot.sweeps >= 10 && snapshot.depth == 3L
        }
        job.cancelAndJoin()

        assertEquals(1, publisher.attempts.count { it == head.original.testId() }, "the backoff keeps the head out")
        assertFalse(stream[1].original.testId() in publisher.attempts, "nothing overtakes the failed head")
        assertFalse(stream[2].original.testId() in publisher.attempts, "nothing overtakes the failed head")
        assertEquals(listOf((other.original as OutboxEvent).id), publisher.published.toList())
        assertEquals(3L, unpublished())
        assertEquals(3L, metrics.snapshot().depth, "the blocked stream stays in the backlog gauge")
        assertEquals(1, metrics.snapshot().eventFailures[head.id])
        val (attemptsMade, nextAttemptAt) = transaction(database) {
            val row = outbox.events.selectAll().first { it[outbox.events.id].value == head.id }
            row[outbox.events.attempts] to row[outbox.events.nextAttemptAt]
        }
        assertEquals(1, attemptsMade)
        assertNotNull(nextAttemptAt, "the failed row carries its backoff")
    }

    @Test
    fun `a deferred stream is retried after its backoff and drains in order`() = runBlocking {
        val partitionKey = UUID.randomUUID()
        val stream = (1..3).map { PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey)) }
        stream.forEach { insert(it) }
        val head = stream.first().original.testId()
        val publisher = FlakyPublisher(head, failures = 2)
        val retrying = Outbox(
            TEST_BUCKET_COUNT,
            retryPolicy = OutboxRetryPolicy(initialDelay = 100.milliseconds, multiplier = 1.0)
        )

        val job = OutboxPublisher(database, listOf(publisher), retrying, delay = POLL_DELAY).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()

        assertEquals(stream.map { it.original.testId() }, publisher.published.toList())
        assertEquals(3, publisher.attempts.count { it == head }, "two failures and the publish")
    }

    @Test
    fun `a row that can not be decoded blocks only its stream`() = runBlocking {
        val partitionKey = UUID.randomUUID()
        val blocked = PublicEvent(PartitionedOutboxEvent(UUID.randomUUID(), partitionKey))
        val free = PublicEvent(OutboxEvent(UUID.randomUUID()))
        val broken = UUID.randomUUID()
        transaction(database) {
            exec(
                "INSERT INTO outbox_events (id, event, bucket, partition_key, trace_information) VALUES (" +
                    "'$broken', '{\"type\": \"com.missing.Event\", \"body\": {}}', " +
                    "${outbox.bucket(partitionKey)}, '$partitionKey', " +
                    "'{\"traceparent\": null, \"tracestate\": null, \"baggage\": null}')"
            )
        }
        insert(blocked)
        insert(free)
        val publisher = CollectingPublisher()
        val metrics = RecordingOutboxMetrics()

        val job = OutboxPublisher(database, listOf(publisher), outbox, delay = POLL_DELAY, metrics = metrics).start()
        awaitUntil { publisher.published.isNotEmpty() && metrics.snapshot().eventFailures.isNotEmpty() }
        job.cancelAndJoin()

        assertEquals(listOf((free.original as OutboxEvent).id), publisher.published.toList())
        assertEquals(2L, unpublished(), "the broken row and the event behind it stay")
        assertTrue((metrics.snapshot().eventFailures[broken] ?: 0) >= 1)
    }

    @Test
    fun `events of one transaction are published in the order they were raised`() = runBlocking {
        val partitionKey = UUID.randomUUID()
        val ids = (1..5).map { UUID.randomUUID() }
        transaction(database) {
            ids.forEach { events.addEvent(PartitionedOutboxEvent(it, partitionKey)) }
        }
        val publisher = CollectingPublisher()

        val job = OutboxPublisher(database, listOf(publisher), outbox, delay = POLL_DELAY).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()

        assertEquals(ids, publisher.published.toList())
    }

    @Test
    fun `migration upgrades the previous schema to a working outbox`() = runBlocking {
        transaction(database) {
            exec("DROP TABLE IF EXISTS outbox_events")
        }
        transaction(database) {
            // the schema the released build ships, from before the buckets
            exec(
                """
                CREATE TABLE outbox_events (
                    id uuid NOT NULL PRIMARY KEY,
                    event jsonb NOT NULL,
                    created_at timestamp with time zone DEFAULT timezone('UTC'::text, statement_timestamp()) NOT NULL,
                    published_at timestamp with time zone,
                    trace_information jsonb NOT NULL
                )
                """.trimIndent()
            )
            exec("CREATE INDEX events_publisshed_idx ON outbox_events (published_at)")
        }
        val old = OutboxEvent(UUID.randomUUID())
        transaction(database) {
            // a row the released build left behind
            exec(
                "INSERT INTO outbox_events (id, event, trace_information) VALUES ('${UUID.randomUUID()}', " +
                    "'{\"type\": \"${OutboxEvent::class.qualifiedName}\", \"body\": {\"id\": \"${old.id}\"}}', " +
                    "'{\"traceparent\": null, \"tracestate\": null, \"baggage\": null}')"
            )
        }
        transaction(database) {
            // the migration the README documents, 16 standing in for the bucketCount of the application
            MIGRATION.forEach { exec(it) }
        }

        val publisher = CollectingPublisher()
        val event = PublicEvent(OutboxEvent(UUID.randomUUID()))
        insert(event)
        val job = OutboxPublisher(database, listOf(publisher), outbox, delay = POLL_DELAY).start()
        awaitUntil { unpublished() == 0L }
        job.cancelAndJoin()

        assertEquals(
            setOf(old.id, (event.original as OutboxEvent).id),
            publisher.published.toSet(),
            "the backfilled row and the new one are both published"
        )
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
            outbox.events.selectAll().map { Triple(it[outbox.events.event], it[outbox.events.id].value, it[outbox.events.bucket]) }
        }

        assertEquals(3, rows.size)
        val partitioned = rows.filter { it.first is PartitionedOutboxEvent }
        assertEquals(2, partitioned.size)
        assertEquals(setOf(outbox.bucket(partitionKey)), partitioned.map { it.third }.toSet())
        val unpartitioned = rows.single { it.first is OutboxEvent }
        assertEquals(outbox.bucket(unpartitioned.second), unpartitioned.third)
    }

    private fun insert(event: PublicEvent) {
        transaction(database) {
            outbox.events.insert {
                it[outbox.events.id] = event.id
                it[outbox.events.event] = event.original
                it[outbox.events.bucket] = outbox.bucket(event.original.partitionKey, event.id)
                it[outbox.events.partitionKey] = event.original.partitionKey ?: event.id
                it[outbox.events.createdAt] = event.createdAt
                it[outbox.events.traceInformation] = TraceInformation(null, null, null)
            }
        }
    }

    private fun unpublished(): Long = transaction(database) { outbox.events.selectAll().count() }

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

    private fun statements(resource: String): List<String> {
        val sql = String(checkNotNull(javaClass.classLoader.getResourceAsStream(resource)).readAllBytes())
        return sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    class CollectingPublisher : Publisher {
        val published: MutableList<UUID> = Collections.synchronizedList(mutableListOf())
        override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
            published.add(event.testId())
        }
    }

    /** Fails every attempt of [failing], collects everything else. */
    class FailingPublisher(private val failing: UUID) : Publisher {
        val attempts: MutableList<UUID> = Collections.synchronizedList(mutableListOf())
        val published: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

        override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
            val id = event.testId()
            attempts.add(id)
            check(id != failing) { "event $id can not be published" }
            published.add(id)
        }
    }

    /** Fails the first [failures] attempts of [flaky], then lets it through. */
    class FlakyPublisher(private val flaky: UUID, private val failures: Int) : Publisher {
        val attempts: MutableList<UUID> = Collections.synchronizedList(mutableListOf())
        val published: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

        override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
            val id = event.testId()
            attempts.add(id)
            check(id != flaky || attempts.count { it == flaky } > failures) { "event $id is not ready yet" }
            published.add(id)
        }
    }

    companion object {
        private const val SCHEMA = "outbox_events_postgres_table.sql"
        private val MIGRATION = listOf(
            "ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS bucket integer",
            "UPDATE outbox_events SET bucket = mod(abs(hashtext(id::text)), $TEST_BUCKET_COUNT) WHERE bucket IS NULL",
            "ALTER TABLE outbox_events ALTER COLUMN bucket SET NOT NULL",
            "ALTER TABLE outbox_events ADD COLUMN partition_key uuid",
            "UPDATE outbox_events SET partition_key = id WHERE partition_key IS NULL",
            "ALTER TABLE outbox_events ALTER COLUMN partition_key SET NOT NULL",
            "ALTER TABLE outbox_events ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY",
            "ALTER TABLE outbox_events ADD COLUMN attempts integer NOT NULL DEFAULT 0",
            "ALTER TABLE outbox_events ADD COLUMN next_attempt_at timestamp with time zone",
            "DROP INDEX IF EXISTS events_publisshed_idx",
            "DROP INDEX IF EXISTS outbox_events_bucket_idx",
            "CREATE INDEX outbox_events_bucket_idx ON outbox_events (bucket, sequence) WHERE published_at IS NULL",
            "CREATE INDEX outbox_events_blocked_idx ON outbox_events (bucket, partition_key) " +
                "WHERE next_attempt_at IS NOT NULL"
        )
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

private fun Event.testId(): UUID = when (this) {
    is OutboxEvent -> id
    is PartitionedOutboxEvent -> id
    else -> error("unexpected event $this")
}
