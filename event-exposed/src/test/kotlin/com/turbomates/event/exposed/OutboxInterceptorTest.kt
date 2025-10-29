package com.turbomates.event.exposed

import com.turbomates.event.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
    @Serializable
    private class TestEvent : Event() {
        override val key get() = TestEvent

        companion object : Key<TestEvent>
    }
}

