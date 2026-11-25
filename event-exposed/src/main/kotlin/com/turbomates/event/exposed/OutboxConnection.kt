package com.turbomates.event.exposed

import java.sql.Connection
import org.jetbrains.exposed.v1.core.statements.api.ExposedSavepoint
import org.jetbrains.exposed.v1.jdbc.DatabaseConnectionAutoRegistration
import org.jetbrains.exposed.v1.jdbc.ExposedConnectionImpl
import org.jetbrains.exposed.v1.jdbc.statements.api.ExposedConnection

/**
 * Lets the events of nested transactions be written with one insert of the outermost one. Optional:
 * without it every nested transaction that raised an event makes an insert of its own.
 *
 * ```
 * Database.connect(
 *     dataSource,
 *     databaseConfig = DatabaseConfig { useNestedTransactions = true },
 *     connectionAutoRegistration = OutboxConnections()
 * )
 * ```
 *
 * The connection of a transaction is no longer a `JdbcConnectionImpl` then, the JDBC one is still its
 * [ExposedConnection.connection].
 *
 * @param connections what makes the connection of Exposed, its own implementation by default.
 */
class OutboxConnections(
    private val connections: DatabaseConnectionAutoRegistration = ExposedConnectionImpl()
) : DatabaseConnectionAutoRegistration {
    override fun invoke(connection: Connection): ExposedConnection<*> = OutboxConnection(connections(connection))
}

/**
 * Keeps the events deferred to the commit of the outermost transaction the way the database keeps its
 * rows: in the order they came, cut by a rollback to a savepoint back to what there was when it was set.
 *
 * A nested transaction is no more than a savepoint for the database, and what a rollback discards is
 * decided by when it was written, not by which transaction wrote it: nested transactions running side by
 * side share one connection and, in Exposed, the name of the savepoint. Postgres takes the latest of a
 * name, and so does this class.
 */
internal class OutboxConnection<T : Any>(
    private val delegate: ExposedConnection<T>
) : ExposedConnection<T> by delegate {
    private val deferred = mutableListOf<PublicEvent>()
    private val savepoints = mutableListOf<Savepoint>()

    /**
     * Takes [raised] in. They are deferred while a savepoint is set: the events deferred before it would be
     * discarded by a rollback to it if they were written now.
     *
     * @return what has to be written now, the deferred events first.
     */
    @Synchronized
    fun defer(raised: List<PublicEvent>): List<PublicEvent> {
        deferred.addAll(raised)
        return if (savepoints.isEmpty()) drain() else emptyList()
    }

    /** Hands the deferred events over to be written by the commit, which ends every savepoint as well. */
    @Synchronized
    fun drain(): List<PublicEvent> = deferred.toList().also { forget() }

    @Synchronized
    override fun rollback() {
        delegate.rollback()
        forget()
    }

    @Synchronized
    override fun setSavepoint(name: String): ExposedSavepoint =
        delegate.setSavepoint(name).also { savepoints.add(Savepoint(name, deferred.size)) }

    @Synchronized
    override fun releaseSavepoint(savepoint: ExposedSavepoint) {
        delegate.releaseSavepoint(savepoint)
        val index = latest(savepoint) ?: return
        savepoints.subList(index, savepoints.size).clear()
    }

    @Synchronized
    override fun rollback(savepoint: ExposedSavepoint) {
        delegate.rollback(savepoint)
        val index = latest(savepoint) ?: return
        savepoints.subList(index + 1, savepoints.size).clear()
        deferred.subList(savepoints[index].deferred, deferred.size).clear()
    }

    private fun latest(savepoint: ExposedSavepoint): Int? =
        savepoints.indexOfLast { it.name == savepoint.name }.takeIf { it >= 0 }

    private fun forget() {
        deferred.clear()
        savepoints.clear()
    }

    private class Savepoint(val name: String, val deferred: Int)
}
