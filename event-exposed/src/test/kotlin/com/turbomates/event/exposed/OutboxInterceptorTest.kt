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

        TelemetryContextHolder.provider = object : TelemetryContextProvider {
            override fun getCurrentContext() = TelemetryContext(testTraceId, testSpanId)
        }

        try {
            transaction(database) {
                Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                    exec(String(readAllBytes()))
                }
            }

            transaction(database) {
                events.addEvent(TestEvent())
            }

            transaction(database) {
                val savedEvent = EventsTable.selectAll().first()
                assertEquals(testTraceId, savedEvent[EventsTable.traceId])
                assertEquals(testSpanId, savedEvent[EventsTable.spanId])
            }
        } finally {
            // Reset to default
            TelemetryContextHolder.provider = NoOpTelemetryContextProvider
        }
    }

    @Test
    fun `should save null telemetry when no provider configured`() {
        // Ensure default NoOp provider is used
        TelemetryContextHolder.provider = NoOpTelemetryContextProvider

        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
        }

        transaction(database) {
            events.addEvent(TestEvent())
        }

        transaction(database) {
            val savedEvent = EventsTable.selectAll().first()
            assertEquals(null, savedEvent[EventsTable.traceId])
            assertEquals(null, savedEvent[EventsTable.spanId])
        }
    }
    @Serializable
    private class TestEvent : Event() {
        override val key get() = TestEvent

        companion object : Key<TestEvent>
    }
}

