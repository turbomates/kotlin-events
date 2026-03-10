package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.Publisher
import com.turbomates.deferredcommand.serializer.DeferredCommandSerializer
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
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
                            publisher.publish(command.original)
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
                    ScheduledDeferredCommand(it[DeferredCommandsTable.command], it[DeferredCommandsTable.id].value)
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
    val executeAt = datetime("execute_at")
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneOffset.UTC) }
}
