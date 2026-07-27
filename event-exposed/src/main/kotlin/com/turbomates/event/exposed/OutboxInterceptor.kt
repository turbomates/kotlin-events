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

/**
 * Writes the events raised in a transaction to the outbox of [outbox], in the same transaction as the
 * business data. Register it with [Outbox.install], it is not picked up by a service loader: it needs
 * the bucket count and the serialization the application is running with.
 */
class OutboxInterceptor(private val outbox: Outbox) : GlobalStatementInterceptor {
    private val telemetryService: Telemetry = ServiceLoader.load(Telemetry::class.java).findFirst().orElse(NoOpTelemetry())

    override fun beforeCommit(transaction: Transaction) {
        save(transaction.events.raiseEvents().toList())
    }

    private fun save(raised: List<Event>) {
        val events = raised.map { PublicEvent(it, traceInformation = telemetryService.traceInformation()) }
        outbox.events.batchInsert(events) { event ->
            this[outbox.events.id] = event.id
            this[outbox.events.event] = event.original
            this[outbox.events.bucket] = outbox.bucket(event.original.partitionKey, event.id)
            this[outbox.events.createdAt] = event.createdAt
            this[outbox.events.traceInformation] = event.traceInformation
        }
        outbox.eventSourcing.batchInsert(raised.filterIsInstance<EventSourcingEvent>()) { event ->
            this[outbox.eventSourcing.id] = UUID.randomUUID()
            this[outbox.eventSourcing.rootId] = event.rootId
            this[outbox.eventSourcing.event] = event
            this[outbox.eventSourcing.createdAt] = event.timestamp
        }
    }
}

val Transaction.events: EventStore by transactionScope { getOrCreate(Key()) { EventStore() } }
