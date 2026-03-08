package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.Scheduler
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class ExposedScheduler(private val database: Database) : Scheduler {
    override suspend fun schedule(command: DeferredCommand) {
        val scheduledCommand = ScheduledDeferredCommand(command)
        newSuspendedTransaction(Dispatchers.IO, database) {
            DeferredCommandsTable.insert {
                it[id] = scheduledCommand.id
                it[DeferredCommandsTable.command] = scheduledCommand.original
                it[DeferredCommandsTable.executeAt] = scheduledCommand.executeAt
                it[DeferredCommandsTable.createdAt] = scheduledCommand.createdAt
            }
        }
    }
}
