package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class OutboxInterceptorTest {
    private lateinit var database: Database
    private val outbox = Outbox(
        TEST_BUCKET_COUNT,
        events = EventRegistry(NamedEvent)
    )
    private lateinit var interceptor: OutboxInterceptor

    @BeforeEach
    fun setUp() {
        val underTest = PostgreSQLContainer("postgres:14").withEnv(mapOf("POSTGRES_USER" to "test", "POSTGRES_PASSWORD" to "test"))
        underTest.start()
        database = Database.connect(
            underTest.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = "test",
            password = "test"
        )
        interceptor = outbox.install()
    }

    @AfterEach
    fun tearDown() {
        JdbcTransaction.globalInterceptors.remove(interceptor)
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

    private fun createTable() {
        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                String(readAllBytes()).split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }
            }
        }
    }

    @Serializable
    private class TestEvent : Event() {
        override val key get() = TestEvent

        companion object : Key<TestEvent>
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
internal class UnregisteredEvent : Event() {
    override val key get() = Companion

    companion object : Event.Key<UnregisteredEvent> {
        override val name = "interceptor.test.unregistered"
    }
}
