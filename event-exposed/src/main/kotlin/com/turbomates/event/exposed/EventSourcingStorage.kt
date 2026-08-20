package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Reads the history of an event sourced aggregate back.
 *
 * @param events the registry the rows were written with — the same instance the [Outbox] holds, it
 * is the one that wrote them. A registry of its own would read only the events registered in both,
 * and silently miss the history written under every name it does not hold.
 */
class EventSourcingStorage(
    private val database: Database,
    events: EventRegistry
) {
    private val sourcing = EventSourcingTable(events)

    fun get(aggregateRoot: String): List<Event> {
        return transaction(database) {
            sourcing
                .selectAll()
                .where { sourcing.rootId eq aggregateRoot }
                .orderBy(sourcing.createdAt, SortOrder.ASC)
                .map { it[sourcing.event] }
        }
    }
}
