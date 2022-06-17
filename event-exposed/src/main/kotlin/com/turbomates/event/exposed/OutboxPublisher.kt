package com.turbomates.event.exposed

import com.turbomates.event.Publisher
import com.turbomates.event.seriazlier.EventSerializer
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class OutboxPublisher(private val database: Database, private val publishers: List<Publisher>, private val delay: Duration = Duration.parse("1s")) :
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val logger = LoggerFactory.getLogger(javaClass)
    suspend fun start() = launch {
        while (isActive) {
            try {
                val events = load()
                events.forEach { event ->
                    try {
                        publish(event.id)
                        publishers.forEach { publisher ->
                            logger.debug("event " + event.id.toString() + " was published by ${publisher.javaClass.name} in worker")
                            publisher.publish(event.original)
                        }
                    } catch (ignore: Throwable) {
                        resetPublish(event.id)
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

    private suspend fun load(limit: Int = 100): List<PublicEvent> {
        return newSuspendedTransaction(Dispatchers.IO, database) {
            Events.select { Events.publishedAt.isNull() }.limit(limit).map {
                PublicEvent(it[Events.event], it[Events.id].value)
            }
        }
    }

    private suspend fun publish(id: UUID) {
        newSuspendedTransaction(Dispatchers.IO, database) {
            Events.update({ Events.id eq id }) {
                it[publishedAt] = LocalDateTime.now()
            }
        }
    }

    private suspend fun resetPublish(id: UUID) {
        newSuspendedTransaction(Dispatchers.IO, database) {
            Events.update({ Events.id eq id }) {
                it[publishedAt] = null
            }
        }
    }
}


internal object Events : UUIDTable("outbox_events") {
    val event = jsonb("event", EventSerializer)
    val publishedAt = datetime("published_at").nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
}
