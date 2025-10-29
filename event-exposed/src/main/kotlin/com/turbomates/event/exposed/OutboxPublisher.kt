package com.turbomates.event.exposed

import com.turbomates.event.Publisher
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
                val events = load(limit)
                events.forEach { event ->
                    try {
                        publishers.forEach { publisher ->
                            logger.debug("event " + event.id.toString() + " was published by ${publisher.javaClass.name} in worker")
                            publisher.publish(event.original)
                        }
                        publish(event.id)
                    } catch (ignore: Throwable) {
                        logger.error("error while publishing event ${event.id}", ignore)
                    }
                }
            } catch (ignore: Throwable) {
                logger.error("error while publishing events", ignore)
            } finally {
                delay(delay)
            }
        }
    }

    private fun load(limit: Int): List<PublicEvent> {
        return transaction(database) {
            EventsTable
                .selectAll()
                .where { EventsTable.publishedAt.isNull() }
                .limit(limit)
                .orderBy(EventsTable.createdAt, org.jetbrains.exposed.sql.SortOrder.ASC)
                .map {
                    PublicEvent(it[EventsTable.event], it[EventsTable.id].value)
                }
        }
    }

    private fun publish(id: UUID) {
        transaction(database) {
            EventsTable.deleteWhere { EventsTable.id eq id }
        }
    }
}


internal object EventsTable : UUIDTable("outbox_events") {
    val event =
        jsonb("event", Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }, EventSerializer)
    val publishedAt = datetime("published_at").nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val traceId = text("trace_id").nullable()
    val spanId = text("span_id").nullable()
}
