package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventStore
import java.util.UUID
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.sql.transactions.transactionScope

class OutboxInterceptor : GlobalStatementInterceptor {

    override fun beforeCommit(transaction: Transaction) {
        val events = transaction.events.raiseEvents().toList()
        events.save()
    }
}

val Transaction.events: EventStore by transactionScope { EventStore() }
fun List<Event>.save() {
    val events = this.map { PublicEvent(it) }
    EventsTable.batchInsert(events) { event ->
        this[EventsTable.id] = event.id
        this[EventsTable.event] = event.original
        this[EventsTable.createdAt] = event.createdAt
    }
    val eventSourcingEvents = this.filterIsInstance<EventSourcingEvent>()
    EventSourcingTable.batchInsert(eventSourcingEvents) { event ->
        this[EventSourcingTable.id] = UUID.randomUUID()
        this[EventSourcingTable.rootId] = event.rootId
        this[EventSourcingTable.event] = event
        this[EventSourcingTable.createdAt] = event.timestamp
    }
}
