package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class OutboxInterceptorTest {
    private lateinit var database: Database
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
        val underTest = PostgreSQLContainer("postgres:14").withEnv(mapOf("POSTGRES_USER" to "test", "POSTGRES_PASSWORD" to "test"))
        underTest.start()
        database = Database.connect(
            underTest.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = "test",
            password = "test",
            databaseConfig = DatabaseConfig { useNestedTransactions = true }
        )
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
