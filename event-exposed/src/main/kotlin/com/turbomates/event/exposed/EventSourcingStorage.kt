package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class EventSourcingStorage(private val database: Database) {
    suspend fun get(aggregateRoot: String): List<Event> {
        return newSuspendedTransaction(Dispatchers.IO, database) {
            EventSourcingTable
                .selectAll()
                .where { EventSourcingTable.id eq aggregateRoot }
                .orderBy(EventSourcingTable.createdAt, SortOrder.ASC)
                .map { it[EventSourcingTable.event] }
        }
    }

    suspend fun add(events: List<EventSourcingEvent<*>>) {
        newSuspendedTransaction(Dispatchers.IO, database) {
            EventSourcingTable.batchInsert(events) { event ->
                this[EventSourcingTable.id] = event.id.toString()
                this[EventSourcingTable.event] = event
                this[EventSourcingTable.createdAt] = event.timestamp
            }
        }
    }
}

object EventSourcingTable : IdTable<String>("event_sourcing") {
    override val id: Column<EntityID<String>> = text("root_id").entityId()
    override val primaryKey = PrimaryKey(id)
    internal val event = jsonb("data", Json, EventSerializer)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
}
