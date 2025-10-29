package com.turbomates.event.exposed

import com.turbomates.event.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer
import kotlinx.serialization.Serializable

class OutboxInterceptorTest {
    private lateinit var database: Database

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
    }

    @Test
    fun `should intercept event`() {
        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
        }
        transaction(database) {
            events.addEvent(TestEvent())
        }
        transaction(database) {
            assertEquals(1, EventsTable.selectAll().count())
        }
    }

    @Test
    fun `should save telemetry context when provider is configured`() {
        // Setup test telemetry provider
        val testTraceId = "test-trace-id-12345"
        val testSpanId = "test-span-id-67890"

        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
        }

        transaction(database) {
            // Set provider for this transaction only
            telemetryContextProvider = object : TelemetryContextProvider {
                override fun getCurrentContext() = TelemetryContext(testTraceId, testSpanId)
            }
            events.addEvent(TestEvent())
        }

        transaction(database) {
            val savedEvent = EventsTable.selectAll().first()
            assertEquals(testTraceId, savedEvent[EventsTable.traceId])
            assertEquals(testSpanId, savedEvent[EventsTable.spanId])
        }
    }

    @Test
    fun `should save null telemetry when no provider configured`() {
        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
        }

        transaction(database) {
            // Use default NoOp provider (no need to set explicitly)
            events.addEvent(TestEvent())
        }

        transaction(database) {
            val savedEvent = EventsTable.selectAll().first()
            assertEquals(null, savedEvent[EventsTable.traceId])
            assertEquals(null, savedEvent[EventsTable.spanId])
        }
    }

    @Test
    fun `should use default provider when configured globally`() {
        val testTraceId = "default-trace-id"
        val testSpanId = "default-span-id"

        // Set provider for this database instance
        database.telemetryProvider = object : TelemetryContextProvider {
            override fun getCurrentContext() = TelemetryContext(testTraceId, testSpanId)
        }

        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
        }

        transaction(database) {
            // No need to set provider - uses database's provider automatically
            events.addEvent(TestEvent())
        }

        transaction(database) {
            val savedEvent = EventsTable.selectAll().first()
            assertEquals(testTraceId, savedEvent[EventsTable.traceId])
            assertEquals(testSpanId, savedEvent[EventsTable.spanId])
        }
    }

    @Test
    fun `should allow override of default provider per transaction`() {
        val defaultTraceId = "default-trace"
        val overrideTraceId = "override-trace"

        // Set provider for database
        database.telemetryProvider = object : TelemetryContextProvider {
            override fun getCurrentContext() = TelemetryContext(defaultTraceId, "default-span")
        }

        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
        }

        // Transaction 1: uses database's provider
        transaction(database) {
            events.addEvent(TestEvent())
        }

        // Transaction 2: overrides with custom provider
        transaction(database) {
            telemetryContextProvider = object : TelemetryContextProvider {
                override fun getCurrentContext() = TelemetryContext(overrideTraceId, "override-span")
            }
            events.addEvent(TestEvent())
        }

        transaction(database) {
            val events = EventsTable.selectAll().toList()
            assertEquals(2, events.size)
            assertEquals(defaultTraceId, events[0][EventsTable.traceId])
            assertEquals(overrideTraceId, events[1][EventsTable.traceId])
        }
    }
    @Serializable
    private class TestEvent : Event() {
        override val key get() = TestEvent

        companion object : Key<TestEvent>
    }
}

