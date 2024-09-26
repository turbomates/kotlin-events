package com.turbomates.event.exposed

import com.turbomates.event.Event
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer
import kotlinx.serialization.Serializable
import java.util.UUID

class EventSourcingTest {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        val underTest = PostgreSQLContainer("postgres:14")
            .withEnv(mapOf("POSTGRES_USER" to "test", "POSTGRES_PASSWORD" to "test"))
        underTest.start()
        database = Database.connect(
            underTest.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = "test",
            password = "test"
        )
    }

    @Test
    fun `write and read from storage`() = runBlocking {
        val eventSourcingStorage = EventSourcingStorage(database)
        val testEvent = TestEventSourcingEvent(UUID.randomUUID().toString())
        transaction(database) {
            EventSourcingEvent::class.java.classLoader.getResourceAsStream("event_sourcing_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }

            runBlocking {
                eventSourcingStorage.add(listOf(testEvent))
            }
        }

        transaction(database) {
            runBlocking {
                assertEquals(1, eventSourcingStorage.get(testEvent.testRootId).size)
            }
        }
    }

    @Serializable
    private data class TestEventSourcingEvent(val testRootId: String) : EventSourcingEvent(testRootId) {
        override val key: Key<out Event>
            get() = Companion

        companion object : Key<TestEventSourcingEvent>
    }
}

