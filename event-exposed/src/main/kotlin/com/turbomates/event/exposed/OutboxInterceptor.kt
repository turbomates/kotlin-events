package com.turbomates.event.exposed

import com.turbomates.event.EventStore
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.transactionScope
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * Writes the events raised in a transaction to the outbox of [outbox], in the same transaction as the
 * business data. Register it with [Outbox.install], it is not picked up by a service loader: it needs
 * the bucket count and the serialization the application is running with.
 *
 * A nested transaction is a savepoint on the connection of the outer one, its commit reaches nothing
 * but the interceptors. So it writes no outbox rows of its own, it hands its events over to the outer
 * transaction, and the outermost one writes them all with one insert. A rollback to the savepoint
 * takes the events of the nested transaction with it, handed over or not.
 *
 * The history of an event sourced aggregate does not wait: the rest of the outer transaction may
 * restore the aggregate from it, so it is written by the transaction that raised the events.
 */
class OutboxInterceptor(private val outbox: Outbox) : GlobalStatementInterceptor {

    override fun beforeCommit(transaction: Transaction) {
        val raised = transaction.events.raiseEvents().toList().map {
            PublicEvent(it, traceInformation = outbox.traceInformation())
        }
        outbox.insertHistory(raised)
        val events = transaction.handedOver.drain() + raised
        val outer = transaction.outer
        if (outer == null) {
            outbox.insertEvents(events)
        } else {
            outer.handedOver.addAll(events.map { HandedOver(transaction, it) })
        }
    }

    override fun beforeRollback(transaction: Transaction) {
        transaction.outer?.handedOver?.removeIf { it.by === transaction }
    }

    private val Transaction.outer: Transaction? get() = (this as? JdbcTransaction)?.outerTransaction

    /** Concurrent: nested transactions of one outer transaction may be running side by side. */
    private val Transaction.handedOver: Queue<HandedOver> get() = getOrCreate(HANDED_OVER) { ConcurrentLinkedQueue() }

    private fun Queue<HandedOver>.drain(): List<PublicEvent> = generateSequence { poll() }.map { it.event }.toList()

    private class HandedOver(val by: Transaction, val event: PublicEvent)

    private companion object {
        val HANDED_OVER = Key<Queue<HandedOver>>()
    }
}

val Transaction.events: EventStore by transactionScope { getOrCreate(Key()) { EventStore() } }
