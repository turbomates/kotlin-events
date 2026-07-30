package com.turbomates.event.exposed

import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * Guards a bucket while a worker publishes it, so two pods never publish the same partition at once.
 *
 * The lock has to be taken inside the batch transaction and released together with it, and it has to
 * be non blocking: a bucket held by another worker is skipped, not waited for.
 */
interface OutboxBucketLock {
    /** @return true when the bucket was acquired by this transaction. */
    fun tryLock(transaction: JdbcTransaction, bucket: Int): Boolean
}

/**
 * `pg_try_advisory_xact_lock(namespace, bucket)`. Postgres only, the lock is released by the
 * database when the batch transaction commits, rolls back or the connection dies.
 *
 * [namespace] separates these locks from any other advisory lock in the same database, applications
 * running several outboxes on one database should give each of them its own namespace.
 */
class PostgresAdvisoryBucketLock(private val namespace: Int = DEFAULT_NAMESPACE) : OutboxBucketLock {
    override fun tryLock(transaction: JdbcTransaction, bucket: Int): Boolean {
        return transaction.exec(
            "SELECT pg_try_advisory_xact_lock(?, ?)",
            listOf(IntegerColumnType() to namespace, IntegerColumnType() to bucket),
            StatementType.SELECT
        ) { resultSet ->
            resultSet.next() && resultSet.getBoolean(1)
        } ?: false
    }

    companion object {
        /** "outbox" as an int, an arbitrary but stable namespace. */
        const val DEFAULT_NAMESPACE: Int = 0x0175B0C5
    }
}

/** Takes every bucket, for single writer deployments and for databases without advisory locks. */
object SingleWorkerBucketLock : OutboxBucketLock {
    override fun tryLock(transaction: JdbcTransaction, bucket: Int): Boolean = true
}
