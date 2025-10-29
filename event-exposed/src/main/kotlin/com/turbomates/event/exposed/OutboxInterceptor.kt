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
        transaction.save(events)
    }
}

val Transaction.events: EventStore by transactionScope { EventStore() }

/**
 * Save events to outbox table with telemetry context.
 * Uses the telemetry context provider associated with this transaction.
 */
fun Transaction.save(events: List<Event>) {
    // Capture telemetry context once for all events in the batch
    val telemetryContext = this.telemetryContextProvider.getCurrentContext()

    val publicEvents = events.map { PublicEvent(it) }
    EventsTable.batchInsert(publicEvents) { event ->
        this[EventsTable.id] = event.id
        this[EventsTable.event] = event.original
        this[EventsTable.createdAt] = event.createdAt
        this[EventsTable.traceId] = telemetryContext.traceId
        this[EventsTable.spanId] = telemetryContext.spanId
    }
    val eventSourcingEvents = events.filterIsInstance<EventSourcingEvent>()
    EventSourcingTable.batchInsert(eventSourcingEvents) { event ->
        this[EventSourcingTable.id] = UUID.randomUUID()
        this[EventSourcingTable.rootId] = event.rootId
        this[EventSourcingTable.event] = event
        this[EventSourcingTable.createdAt] = event.timestamp
    }
}
