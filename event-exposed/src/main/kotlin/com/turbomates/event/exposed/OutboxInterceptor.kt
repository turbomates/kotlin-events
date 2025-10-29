package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventStore
import com.turbomates.event.NoOpTelemetry
import com.turbomates.event.Telemetry
import java.util.ServiceLoader
import java.util.UUID
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.transactionScope
import org.jetbrains.exposed.v1.jdbc.batchInsert

class OutboxInterceptor : GlobalStatementInterceptor {
    private val telemetryService: Telemetry = ServiceLoader.load(Telemetry::class.java).findFirst().orElse(NoOpTelemetry())

    override fun beforeCommit(transaction: Transaction) {
        val events = transaction.events.raiseEvents().toList()
        events.save(telemetryService)
    }
}

val Transaction.events: EventStore by transactionScope { getOrCreate(Key()) { EventStore() } }

fun List<Event>.save(telemetryService: Telemetry) {
    val events = this.map {
        PublicEvent(
            it,
            traceInformation = telemetryService.traceInformation()
        )
    }
    EventsTable.batchInsert(events) { event ->
        this[EventsTable.id] = event.id
        this[EventsTable.event] = event.original
        this[EventsTable.createdAt] = event.createdAt
        this[EventsTable.traceInformation] = event.traceInformation
    }
    val eventSourcingEvents = this.filterIsInstance<EventSourcingEvent>()
    EventSourcingTable.batchInsert(eventSourcingEvents) { event ->
        this[EventSourcingTable.id] = UUID.randomUUID()
        this[EventSourcingTable.rootId] = event.rootId
        this[EventSourcingTable.event] = event
        this[EventSourcingTable.createdAt] = event.timestamp
    }
}
