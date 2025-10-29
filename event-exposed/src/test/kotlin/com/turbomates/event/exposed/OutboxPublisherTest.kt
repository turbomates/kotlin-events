package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.Publisher
import com.turbomates.event.TraceInformation
import com.turbomates.event.exposed.serializer.UUIDSerializer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class OutboxPublisherTest {
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
    fun `successfully publishing`() = runBlocking {
        val outboxPublisher = OutboxPublisher(database, listOf(LocalPublisher))
        val id = UUID.randomUUID()
        val event = PublicEvent(OutboxEvent(id))
        transaction(database) {
            Event::class.java.classLoader.getResourceAsStream("outbox_events_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
            EventsTable.insert {
                it[EventsTable.id] = event.id
                it[EventsTable.event] = event.original
                it[EventsTable.createdAt] = event.createdAt
            }
        }
        outboxPublisher.start().cancelAndJoin()
        assertEquals(1, LocalPublisher.publishedEvents.count())
        assertEquals(id, LocalPublisher.publishedEvents.first().id)
    }


    object LocalPublisher : Publisher {
        val publishedEvents = mutableListOf<OutboxEvent>()
        override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
            publishedEvents.add(event as OutboxEvent)
        }
    }
}

@Serializable
data class OutboxEvent(@Serializable(with = UUIDSerializer::class) val id: UUID) : Event() {
    override val key: Key<out Event> get() = OutboxEvent

    companion object : Key<OutboxEvent>
}
