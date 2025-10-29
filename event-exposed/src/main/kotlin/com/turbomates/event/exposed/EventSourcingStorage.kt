package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.jsonb

class EventSourcingStorage(private val database: Database) {
    fun get(aggregateRoot: String): List<Event> {
        return transaction(database) {
            EventSourcingTable
                .selectAll()
                .where { EventSourcingTable.rootId eq aggregateRoot }
                .orderBy(EventSourcingTable.createdAt, SortOrder.ASC)
                .map { it[EventSourcingTable.event] }
        }
    }

    fun add(events: List<EventSourcingEvent>) {
        transaction(database) {
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
