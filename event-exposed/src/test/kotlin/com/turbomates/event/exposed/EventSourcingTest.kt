package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.TraceInformation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

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
    fun `events written through the outbox are read back from storage`() {
        val outbox = Outbox(TEST_BUCKET_COUNT)
        val eventSourcingStorage = EventSourcingStorage(database)
        val testEvent = TestEventSourcingEvent(UUID.randomUUID().toString())
        transaction(database) {
            listOf("event_sourcing_postgres_table.sql", "outbox_events_postgres_table.sql").forEach { resource ->
                EventSourcingEvent::class.java.classLoader.getResourceAsStream(resource)?.apply {
                    exec(String(readAllBytes()))
                }
            }
        }

        transaction(database) {
            outbox.batchEventsInsert(listOf(PublicEvent(testEvent, traceInformation = TraceInformation(null, null, null))))
        }

        assertEquals(1, eventSourcingStorage.get(testEvent.testRootId).size)
    }
}

@Serializable
private data class TestEventSourcingEvent(val testRootId: String) : EventSourcingEvent(testRootId) {
    override val key: Key<out Event>
        get() = Companion

    companion object : Key<TestEventSourcingEvent>
}
