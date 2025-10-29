package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventStore
import com.turbomates.event.NoOpTelemetryService
import com.turbomates.event.TelemetryService
import java.util.ServiceLoader
import java.util.UUID
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.sql.transactions.transactionScope

class OutboxInterceptor : GlobalStatementInterceptor {

    private val telemetryService by lazy {
        ServiceLoader.load(TelemetryService::class.java).findFirst().orElse(NoOpTelemetryService())
    }

    override fun beforeCommit(transaction: Transaction) {
        val events = transaction.events.raiseEvents().toList()
        events.save(telemetryService)
    }
}

val Transaction.events: EventStore by transactionScope { EventStore() }
fun List<Event>.save(telemetryService: TelemetryService) {
    val events = this.map {
        PublicEvent(
            it,
            traceparent = telemetryService.traceparent,
            spanId = telemetryService.spanId
        )
    }
    EventsTable.batchInsert(events) { event ->
        this[EventsTable.id] = event.id
        this[EventsTable.event] = event.original
        this[EventsTable.createdAt] = event.createdAt
        this[EventsTable.traceparent] = event.traceparent
        this[EventsTable.spanId] = event.spanId
    }
    val eventSourcingEvents = this.filterIsInstance<EventSourcingEvent>()
    EventSourcingTable.batchInsert(eventSourcingEvents) { event ->
        this[EventSourcingTable.id] = UUID.randomUUID()
        this[EventSourcingTable.rootId] = event.rootId
        this[EventSourcingTable.event] = event
        this[EventSourcingTable.createdAt] = event.timestamp
    }
}
