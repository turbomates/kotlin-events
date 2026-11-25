package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.core.transactions.withThreadLocalTransaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.DatabaseConnectionAutoRegistration
import org.jetbrains.exposed.v1.jdbc.ExposedConnectionImpl
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class OutboxInterceptorTest {
    private lateinit var database: Database
    private lateinit var postgres: PostgreSQLContainer<*>
    private val telemetry = SettableTelemetry()
    private val registry = EventRegistry(NamedEvent, SourcedEvent)
    private val outbox = Outbox(
        TEST_BUCKET_COUNT,
        events = registry,
        telemetryService = telemetry
    )
    private lateinit var interceptor: OutboxInterceptor
    private val inserts = OutboxInserts()

    @BeforeEach
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:14")
            .withEnv(mapOf("POSTGRES_USER" to "test", "POSTGRES_PASSWORD" to "test"))
        postgres.start()
        database = connect(OutboxConnections())
        interceptor = outbox.install()
        JdbcTransaction.globalInterceptors.add(inserts)
    }

    @AfterEach
    fun tearDown() {
        JdbcTransaction.globalInterceptors.remove(interceptor)
        JdbcTransaction.globalInterceptors.remove(inserts)
    }

    @Test
    fun `should intercept event`() {
        createTable()
        transaction(database) {
            events.addEvent(TestEvent())
        }
        transaction(database) {
            assertEquals(1, outbox.eventsTable.selectAll().count())
        }
    }

    @Test
    fun `an event of a registered name is written`() {
        createTable()
        transaction(database) {
            events.addEvent(NamedEvent())
        }
        transaction(database) {
            assertEquals(1, outbox.eventsTable.selectAll().count())
        }
    }

    @Test
    fun `an event of a name nothing registered is written all the same`() {
        // The business transaction does not depend on the delivery of its events: an unregistered
        // name costs a row the sweep cannot decode, which it defers and reports, not the work that
        // raised it. Registration is a fact about the build — the processor catalogs every event of
        // a module and fails the build on the ones it cannot carry.
        createTable()
        transaction(database) {
            events.addEvent(UnregisteredEvent())
        }
        transaction(database) {
            assertEquals(1, outbox.eventsTable.selectAll().count())
        }
    }

    @Test
    fun `events of nested transactions are written by one insert of the outermost one`() {
        createTable()
        val first = TestEvent()
        val innermost = TestEvent()
        val second = TestEvent()
        val outer = TestEvent()
        transaction(database) {
            transaction(database) { events.addEvent(first) }
            transaction(database) {
                transaction(database) { events.addEvent(innermost) }
                events.addEvent(second)
            }
            assertEquals(emptyList(), stored())
            events.addEvent(outer)
        }
        assertEquals(listOf(first, innermost, second, outer).map { it.eventId }, transaction(database) { stored() })
        assertEquals(1, inserts.count)
    }

    @Test
    fun `an event of a nested transaction is written though the outermost one raised nothing`() {
        createTable()
        val nested = TestEvent()
        transaction(database) {
            transaction(database) { events.addEvent(nested) }
        }
        assertEquals(listOf(nested.eventId), transaction(database) { stored() })
    }

    @Test
    fun `the history of an aggregate is written by the nested transaction, its outer one may read it back`() {
        createTable()
        val rootId = UUID.randomUUID().toString()
        val storage = EventSourcingStorage(database, registry)
        transaction(database) {
            transaction(database) { events.addEvent(SourcedEvent(rootId)) }
            assertEquals(1, storage.get(rootId).size)
            assertEquals(emptyList(), stored())
            transaction(database) {
                events.addEvent(SourcedEvent(rootId))
                commit()
                rollback()
            }
            assertEquals(1, storage.get(rootId).size)
        }
        assertEquals(1, storage.get(rootId).size)
        assertEquals(1, transaction(database) { stored() }.size)
        assertEquals(1, inserts.count)
    }

    @Test
    fun `events of a nested transaction that failed are not written with the outer one`() {
        createTable()
        val outer = TestEvent()
        val sibling = TestEvent()
        transaction(database) {
            events.addEvent(outer)
            transaction(database) { events.addEvent(sibling) }
            assertFailsWith<IllegalStateException> {
                transaction(database) {
                    transaction(database) { events.addEvent(TestEvent()) }
                    events.addEvent(TestEvent())
                    error("rolled back to the savepoint")
                }
            }
        }
        assertEquals(setOf(outer.eventId, sibling.eventId), transaction(database) { stored() }.toSet())
    }

    @Test
    fun `events of a nested transaction are gone with the outer one that failed`() {
        createTable()
        assertFailsWith<IllegalStateException> {
            transaction(database) {
                transaction(database) { events.addEvent(TestEvent()) }
                error("rolled back")
            }
        }
        assertEquals(emptyList(), transaction(database) { stored() })
    }

    @Test
    fun `events raised after the outer transaction rolled back are written without the ones before`() {
        createTable()
        val after = TestEvent()
        transaction(database) {
            transaction(database) { events.addEvent(TestEvent()) }
            rollback()
            transaction(database) { events.addEvent(after) }
        }
        assertEquals(listOf(after.eventId), transaction(database) { stored() })
    }

    @Test
    fun `events a nested transaction committed by hand are taken back when it rolls back`() {
        createTable()
        val sibling = TestEvent()
        transaction(database) {
            transaction(database) { events.addEvent(sibling) }
            transaction(database) {
                transaction(database) { events.addEvent(TestEvent()) }
                events.addEvent(TestEvent())
                commit()
                rollback()
            }
        }
        assertEquals(listOf(sibling.eventId), transaction(database) { stored() })
    }

    @Test
    fun `an event keeps the trace of the transaction that raised it`() {
        createTable()
        val nested = TestEvent()
        val outer = TestEvent()
        transaction(database) {
            transaction(database) {
                telemetry.traceparent = "nested"
                events.addEvent(nested)
            }
            telemetry.traceparent = "outer"
            events.addEvent(outer)
        }
        val traces = transaction(database) {
            outbox.eventsTable.selectAll().associate {
                it[outbox.eventsTable.id].value to it[outbox.eventsTable.traceInformation]?.traceparent
            }
        }
        assertEquals(mapOf(nested.eventId to "nested", outer.eventId to "outer"), traces)
    }

    @Test
    fun `nested transactions running side by side lose no event`() {
        createTable()
        val raised = List(PARALLEL_TRANSACTIONS) { TestEvent() }
        runBlocking {
            withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    // Opens the connection: Exposed does it lazily and without a lock, a race would open several.
                    assertEquals(emptyList(), stored())
                    coroutineScope {
                        raised.map { event ->
                            async { suspendTransaction(database) { events.addEvent(event) } }
                        }.awaitAll()
                    }
                }
            }
        }
        assertEquals(raised.map { it.eventId }.toSet(), transaction(database) { stored() }.toSet())
        assertEquals(1, inserts.count)
    }

    @Test
    fun `an event of a deeper transaction stays before the one its neighbour committed later`() {
        createTable()
        val deeper = TestEvent()
        val later = TestEvent()
        transaction(database) {
            val neighbour = nested(this)
            val parent = nested(this)
            nested(parent).apply { raise(deeper); commit() }
            neighbour.apply { raise(later); commit() }
            parent.commit()
        }
        assertEquals(listOf(deeper, later).map { it.eventId }, transaction(database) { stored() })
        assertEquals(1, inserts.count)
    }

    @Test
    fun `an event is gone with the data a rollback of the overlapping transaction discarded`() {
        createTable()
        val before = TestEvent()
        transaction(database) {
            transaction(database) { events.addEvent(before) }
            val first = nested(this)
            nested(this).apply { raise(TestEvent()); commit() }
            first.rollback()
        }
        assertEquals(listOf(before.eventId), transaction(database) { stored() })
    }

    @Test
    fun `events written by hand do not overtake the deferred ones`() {
        createTable()
        val nested = TestEvent()
        val inNested = TestEvent()
        val inOuter = TestEvent()
        val last = TestEvent()
        transaction(database) {
            transaction(database) { events.addEvent(nested) }
            transaction(database) { outbox.batchEventsInsert(listOf(byHand(inNested))) }
            outbox.batchEventsInsert(listOf(byHand(inOuter)))
            transaction(database) { events.addEvent(last) }
        }
        assertEquals(listOf(nested, inNested, inOuter, last).map { it.eventId }, transaction(database) { stored() })
    }

    @Test
    fun `events written by hand are written at once while nothing is nested`() {
        createTable()
        val event = TestEvent()
        transaction(database) {
            outbox.batchEventsInsert(listOf(byHand(event)))
            assertEquals(listOf(event.eventId), stored())
        }
        assertEquals(listOf(event.eventId), transaction(database) { stored() })
    }

    @Test
    fun `events written by hand in a nested transaction are gone with it`() {
        createTable()
        val sibling = TestEvent()
        transaction(database) {
            transaction(database) { events.addEvent(sibling) }
            transaction(database) {
                outbox.batchEventsInsert(listOf(byHand(TestEvent())))
                rollback()
            }
        }
        assertEquals(listOf(sibling.eventId), transaction(database) { stored() })
    }

    @Test
    fun `events written by hand through an outbox that is not installed are not deferred`() {
        createTable()
        val event = TestEvent()
        JdbcTransaction.globalInterceptors.remove(interceptor)
        transaction(database) {
            transaction(database) { outbox.batchEventsInsert(listOf(byHand(event))) }
        }
        assertEquals(listOf(event.eventId), transaction(database) { stored() })
    }

    @Test
    fun `without the outbox connections every nested transaction writes its events itself`() {
        createTable()
        val plain = connect(ExposedConnectionImpl())
        val nested = TestEvent()
        val outer = TestEvent()
        transaction(plain) {
            transaction(plain) { events.addEvent(nested) }
            assertEquals(listOf(nested.eventId), stored())
            events.addEvent(outer)
        }
        assertEquals(listOf(nested, outer).map { it.eventId }, transaction(database) { stored() })
        assertEquals(2, inserts.count)
    }

    @Test
    fun `the deferred events are what the rows written at once would be, however transactions overlap`() {
        createTable()
        transaction(database) { exec("CREATE TABLE written_at_once (sequence bigserial, id uuid)") }
        val writer = AtOnce()
        JdbcTransaction.globalInterceptors.add(0, writer)
        val compared = try {
            (0 until RANDOM_RUNS).count { seed -> overlapAtRandom(Random(seed)) }
        } finally {
            JdbcTransaction.globalInterceptors.remove(writer)
        }
        // The rest ended on an error of the database: overlapping savepoints share a name in Exposed.
        assertTrue(compared > RANDOM_RUNS / 2, "only $compared runs were compared")
    }

    /** @return false when the database aborted the transaction, there is nothing to compare. */
    private fun overlapAtRandom(random: Random): Boolean {
        transaction(database) { exec("TRUNCATE written_at_once, outbox_events") }
        val root = database.transactionManager.newTransaction()
        val open = mutableListOf(root)
        val finished = runCatching {
            repeat(RANDOM_STEPS) {
                val current = open.random(random)
                val leaf = current !== root && open.none { it.outerTransaction === current }
                when (random.nextInt(6)) {
                    0, 1 -> open.add(nested(current))
                    2, 3 -> current.raise(TestEvent())
                    4 -> if (leaf) current.rollback()
                    else -> if (leaf) {
                        current.commit()
                        if (random.nextBoolean()) current.close()
                        open.remove(current)
                    }
                }
            }
            root.commit()
        }.isSuccess
        if (!finished) root.rollback()
        root.close()
        if (finished) transaction(database) { assertEquals(writtenAtOnce(), stored()) }
        return finished
    }

    private fun JdbcTransaction.writtenAtOnce() = exec("SELECT id FROM written_at_once ORDER BY sequence") { rows ->
        generateSequence { if (rows.next()) rows.getObject(1, UUID::class.java) else null }.toList()
    }

    private fun nested(outer: JdbcTransaction) = database.transactionManager.newTransaction(outerTransaction = outer)

    @OptIn(InternalApi::class)
    private fun JdbcTransaction.raise(event: Event) {
        getOrCreate(RAISED) { mutableListOf() }.add(event)
        withThreadLocalTransaction(this) { events.addEvent(event) }
    }

    private fun byHand(event: Event) = PublicEvent(event, TraceInformation(null, null, null))

    private fun connect(connections: DatabaseConnectionAutoRegistration) = Database.connect(
        postgres.jdbcUrl,
        driver = "org.postgresql.Driver",
        user = "test",
        password = "test",
        databaseConfig = DatabaseConfig { useNestedTransactions = true },
        connectionAutoRegistration = connections
    )

    private fun stored(): List<UUID> = outbox.eventsTable
        .selectAll()
        .orderBy(outbox.eventsTable.sequence to SortOrder.ASC)
        .map { it[outbox.eventsTable.id].value }

    private fun createTable() {
        transaction(database) {
            listOf("outbox_events_postgres_table.sql", "event_sourcing_postgres_table.sql").forEach { resource ->
                Event::class.java.classLoader.getResourceAsStream(resource)?.apply {
                    String(readAllBytes()).split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }
                }
            }
        }
    }

    @Serializable
    private class TestEvent : Event() {
        override val key get() = TestEvent

        companion object : Key<TestEvent>
    }

    private inner class OutboxInserts : GlobalStatementInterceptor {
        var count = 0

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi
        ) {
            val statement = contexts.first().statement
            if (statement is InsertStatement<*> && statement.table == outbox.eventsTable) count++
        }
    }

    /** What the interceptor did before it deferred anything: a row for every event, on the commit of its transaction. */
    private class AtOnce : GlobalStatementInterceptor {
        override fun beforeCommit(transaction: Transaction) {
            transaction.getUserData(RAISED)?.forEach {
                (transaction as JdbcTransaction).exec("INSERT INTO written_at_once (id) VALUES ('${it.eventId}')")
            }
        }
    }

    private class SettableTelemetry : Telemetry {
        var traceparent: String? = null

        override fun traceInformation() = TraceInformation(traceparent, null, null)

        override suspend fun link(
            traceInformation: TraceInformation,
            spanName: String,
            attributes: Map<String, String>,
            block: suspend TraceInformation.() -> Unit
        ) = block(traceInformation)
    }

    private companion object {
        const val PARALLEL_TRANSACTIONS = 50
        const val RANDOM_RUNS = 500
        const val RANDOM_STEPS = 30
        val RAISED = Key<MutableList<Event>>()
    }
}

@Serializable
internal class NamedEvent : Event() {
    override val key get() = Companion

    companion object : Event.Key<NamedEvent> {
        override val name = "interceptor.test.named"
    }
}

@Serializable
internal class SourcedEvent(val root: String) : EventSourcingEvent(root) {
    override val key get() = Companion

    companion object : Event.Key<SourcedEvent> {
        override val name = "interceptor.test.sourced"
    }
}

@Serializable
internal class UnregisteredEvent : Event() {
    override val key get() = Companion

    companion object : Event.Key<UnregisteredEvent> {
        override val name = "interceptor.test.unregistered"
    }
}
