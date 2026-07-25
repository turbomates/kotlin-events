package com.turbomates.event.exposed

import java.util.UUID
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Bucket layout of the outbox table.
 *
 * [COUNT] is a compile time constant on purpose: it is a property of the data already written to
 * `outbox_events`, not a per pod setting. Changing it re-maps every partition key, so events of the
 * same stream would end up in two buckets at once and could be published concurrently by two
 * workers. To make an accidental change impossible, the value used to initialize the database is
 * stored in `outbox_settings` and [verify] fails on startup when the two disagree.
 */
object OutboxBuckets {
    const val COUNT: Int = 16

    internal const val BUCKET_COUNT_SETTING = "bucket_count"

    /** Bucket of an outbox row: the event partition key when it has one, its own id otherwise. */
    fun of(partitionKey: UUID?, eventId: UUID): Int = of(partitionKey ?: eventId)

    fun of(key: UUID): Int = Math.floorMod(key.mostSignificantBits xor key.leastSignificantBits, COUNT)

    /**
     * Stores [COUNT] in the database on the first run and fails when a later build disagrees with
     * the value the existing rows were written with.
     *
     * @throws OutboxBucketCountMismatchException when the database holds a different bucket count.
     */
    fun verify(database: Database) {
        val stored = try {
            transaction(database) {
                OutboxSettingsTable.insertIgnore {
                    it[settingName] = BUCKET_COUNT_SETTING
                    it[settingValue] = COUNT
                }
                OutboxSettingsTable
                    .selectAll()
                    .where { OutboxSettingsTable.settingName eq BUCKET_COUNT_SETTING }
                    .single()[OutboxSettingsTable.settingValue]
            }
        } catch (exception: ExposedSQLException) {
            throw OutboxSettingsUnavailableException(exception)
        }
        if (stored != COUNT) {
            throw OutboxBucketCountMismatchException(stored, COUNT)
        }
    }
}

class OutboxBucketCountMismatchException(val stored: Int, val expected: Int) : IllegalStateException(
    "Outbox bucket count mismatch: outbox_events was written with $stored buckets, this build uses $expected. " +
        "Re-bucketing a live outbox splits a partition across two buckets, drain the table and " +
        "update outbox_settings.bucket_count before changing OutboxBuckets.COUNT."
)

class OutboxSettingsUnavailableException(cause: Throwable) : IllegalStateException(
    "Unable to read outbox_settings. Add the table and the outbox_events.bucket column to the " +
        "application migrations, see outbox_events_postgres_table.sql.",
    cause
)

internal object OutboxSettingsTable : Table("outbox_settings") {
    val settingName = text("name")
    val settingValue = integer("value")
    override val primaryKey = PrimaryKey(settingName)
}
