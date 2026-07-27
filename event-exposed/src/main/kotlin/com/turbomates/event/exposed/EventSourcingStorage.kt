package com.turbomates.event.exposed

import com.turbomates.event.Event
import java.util.UUID
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class EventSourcingStorage(
    private val database: Database,
    serialization: EventSerialization = EventSerialization()
) {
    private val sourcing = EventSourcingTable(serialization)

    fun get(aggregateRoot: String): List<Event> {
        return transaction(database) {
            sourcing
                .selectAll()
                .where { sourcing.rootId eq aggregateRoot }
                .orderBy(sourcing.createdAt, SortOrder.ASC)
                .map { it[sourcing.event] }
        }
    }

    fun add(sourced: List<EventSourcingEvent>) {
        transaction(database) {
            sourcing.batchInsert(sourced) { event ->
                this[sourcing.id] = UUID.randomUUID()
                this[sourcing.rootId] = event.rootId
                this[sourcing.event] = event
                this[sourcing.createdAt] = event.timestamp
            }
        }
    }
}
