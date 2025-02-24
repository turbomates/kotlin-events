package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class EventSourcingStorage(private val database: Database) {
    suspend fun get(aggregateRoot: String): List<Event> {
        return newSuspendedTransaction(Dispatchers.IO, database) {
            EventSourcingTable
                .selectAll()
                .where { EventSourcingTable.rootId eq aggregateRoot }
                .orderBy(EventSourcingTable.createdAt, SortOrder.ASC)
                .map { it[EventSourcingTable.event] }
        }
    }

    suspend fun add(events: List<EventSourcingEvent>) {
        newSuspendedTransaction(Dispatchers.IO, database) {
            EventSourcingTable.batchInsert(events) { event ->
                this[EventSourcingTable.id] = UUID.randomUUID()
                this[EventSourcingTable.rootId] = event.rootId
                this[EventSourcingTable.event] = event
                this[EventSourcingTable.createdAt] = event.timestamp
            }
        }
    }
}

internal object EventSourcingTable : UUIDTable("event_sourcing") {
    val rootId = text("root_id")
    internal val event =
        jsonb("data", Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }, EventSerializer)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}
