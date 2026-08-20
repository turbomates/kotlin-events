package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.EventStore
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.transactionScope

/**
 * Writes the events raised in a transaction to the outbox of [outbox], in the same transaction as the
 * business data. Register it with [Outbox.install], it is not picked up by a service loader: it needs
 * the bucket count and the serialization the application is running with.
 */
class OutboxInterceptor(private val outbox: Outbox) : GlobalStatementInterceptor {

    override fun beforeCommit(transaction: Transaction) {
        save(transaction.events.raiseEvents().toList())
    }

    private fun save(raised: List<Event>) {
        val events = raised.map { PublicEvent(it, traceInformation = outbox.traceInformation()) }
        outbox.batchEventsInsert(events)
    }
}

val Transaction.events: EventStore by transactionScope { getOrCreate(Key()) { EventStore() } }
