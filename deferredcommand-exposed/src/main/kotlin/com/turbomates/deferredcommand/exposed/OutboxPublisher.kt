package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.Publisher
import com.turbomates.deferredcommand.serializer.DeferredCommandSerializer
import com.turbomates.event.TraceInformation
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.jsonb
import org.slf4j.LoggerFactory

class OutboxPublisher(
    private val database: Database,
    private val publishers: List<Publisher>,
    private val limit: Int = 1000,
    private val delay: Duration = Duration.parse("1s"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : CoroutineScope by CoroutineScope(dispatcher) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun start() = launch {
        while (isActive) {
            try {
                val commands = load(limit)
                commands.forEach { command ->
                    try {
                        publishers.forEach { publisher ->
                            logger.debug("deferred command ${command.id} was published by ${publisher.javaClass.name} in worker")
                            publisher.publish(command.original, command.traceInformation)
                        }
                        publish(command.id)
                    } catch (ignore: Throwable) {
                        logger.error("error while publishing deferred command ${command.id}", ignore)
                    }
                }
            } catch (ignore: Throwable) {
                logger.error("error while publishing deferred commands", ignore)
            } finally {
                delay(delay)
            }
        }
    }

    private fun load(limit: Int): List<ScheduledDeferredCommand> {
        return transaction(database) {
            DeferredCommandsTable
                .selectAll()
                .where { DeferredCommandsTable.executeAt lessEq LocalDateTime.now(ZoneOffset.UTC) }
                .limit(limit)
                .orderBy(DeferredCommandsTable.executeAt, SortOrder.ASC)
                .map {
                    ScheduledDeferredCommand(
                        original = it[DeferredCommandsTable.command],
                        id = it[DeferredCommandsTable.id].value,
                        traceInformation = it[DeferredCommandsTable.traceInformation]
                    )
                }
        }
    }

    private fun publish(id: UUID) {
        transaction(database) {
            DeferredCommandsTable.deleteWhere { DeferredCommandsTable.id eq id }
        }
    }
}

internal object DeferredCommandsTable : UUIDTable("deferred_commands") {
    val command =
        jsonb("command", Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }, DeferredCommandSerializer)
    val traceInformation =
        jsonb(
            "trace_information", Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false },
            TraceInformation.serializer()
        ).nullable()
    val executeAt = datetime("execute_at")
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}
