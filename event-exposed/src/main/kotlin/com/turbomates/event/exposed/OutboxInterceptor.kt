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
        raised.forEach { checkReadable(it) }
        val events = raised.map { PublicEvent(it, traceInformation = outbox.traceInformation()) }
        outbox.batchEventsInsert(events)
    }

    /**
     * Fails the transaction that raised an event the outbox cannot read back, instead of letting it
     * commit a row that has to be deleted by hand later. It costs the author of a new event one
     * failed request, right where the event is raised; the alternative surfaces days later, in the
     * worker or in another service, as a stream that stopped moving.
     */
    private fun checkReadable(event: Event) {
        check(outbox.readable(event.key)) {
            "Event '${event.key.name}' is not registered in the EventRegistry of this outbox: it " +
                "would be written under a name nothing can resolve back and would never be " +
                "published. Add EventRegistry(${event::class.simpleName}) to its EventSerialization."
        }
    }
}

val Transaction.events: EventStore by transactionScope { getOrCreate(Key()) { EventStore() } }
