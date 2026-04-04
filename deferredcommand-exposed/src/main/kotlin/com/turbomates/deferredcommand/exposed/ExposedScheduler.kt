package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.Scheduler
import com.turbomates.event.NoOpTelemetry
import com.turbomates.event.Telemetry
import java.util.ServiceLoader
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedScheduler(
    private val database: Database,
    private val telemetryService: Telemetry = ServiceLoader.load(Telemetry::class.java).findFirst().orElse(NoOpTelemetry())
) : Scheduler {
    override suspend fun schedule(command: DeferredCommand) {
        val scheduledCommand = ScheduledDeferredCommand(
            original = command,
            traceInformation = telemetryService.traceInformation()
        )

        transaction(database) {
            DeferredCommandsTable.insert {
                it[DeferredCommandsTable.id] = scheduledCommand.id
                it[DeferredCommandsTable.command] = scheduledCommand.original
                it[DeferredCommandsTable.executeAt] = scheduledCommand.executeAt
                it[DeferredCommandsTable.createdAt] = scheduledCommand.createdAt
                it[DeferredCommandsTable.traceInformation] = scheduledCommand.traceInformation
            }
        }
    }
}
