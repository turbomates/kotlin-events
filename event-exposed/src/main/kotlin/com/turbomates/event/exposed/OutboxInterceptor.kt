package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventStore
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.sql.transactions.transactionScope

class OutboxInterceptor : GlobalStatementInterceptor {
    @Suppress("UNCHECKED_CAST")
    override fun beforeCommit(transaction: Transaction) {
        val events = transaction.events.raiseEvents().toList()
        events.save()
    }
}

val Transaction.events: EventStore by transactionScope { EventStore() }
internal fun List<Event>.save() {
    val events = this.map { PublicEvent(it) }
    Events.batchInsert(events) { event ->
        this[Events.id] = event.id
        this[Events.event] = event.original
        this[Events.createdAt] = event.createdAt
    }
    val eventSourcingEvents = this.filterIsInstance<EventSourcingEvent<*>>()
    EventSourcingTable.batchInsert(eventSourcingEvents) { event ->
        this[EventSourcingTable.id] = event.id.toString()
        this[EventSourcingTable.event] = event
        this[EventSourcingTable.createdAt] = event.timestamp
    }
}
