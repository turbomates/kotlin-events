package com.turbomates.event.exposed

import com.turbomates.event.EventStore
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
 * A nested transaction is a savepoint on the connection of the outer one. With [OutboxConnections] its
 * commit defers the events, and the commit of the outermost transaction writes all of them at once.
 */
class OutboxInterceptor(internal val outbox: Outbox) : GlobalStatementInterceptor {

    override fun beforeCommit(transaction: Transaction) {
        val raised = transaction.events.raiseEvents().toList().map {
            PublicEvent(it, traceInformation = outbox.traceInformation())
        }
        if (transaction is JdbcTransaction && transaction.outerTransaction != null) {
            outbox.batchEventsInsert(raised)
        } else {
            outbox.write(transaction.deferring?.drain().orEmpty() + raised, history = raised)
        }
    }
}

/** The connection of an outermost transaction, once it deferred an event: asking for it opens one. */
internal var Transaction.deferring: OutboxConnection<*>?
    get() = getUserData(DEFERRING)
    set(value) {
        if (value != null) putUserData(DEFERRING, value)
    }

private val DEFERRING = Key<OutboxConnection<*>>()

val Transaction.events: EventStore by transactionScope { getOrCreate(Key()) { EventStore() } }
