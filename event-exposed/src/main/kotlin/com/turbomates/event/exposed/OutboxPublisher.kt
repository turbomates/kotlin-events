package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.Publisher
import com.turbomates.event.TraceInformation
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

/**
 * Publishes the outbox in buckets instead of scanning the whole table.
 *
 * A sweep walks every bucket once, starting at a rotating position so workers that poll in lockstep
 * do not keep fighting over the same bucket. A bucket is taken with a non blocking lock and held for
 * the whole batch, buckets held by another worker are skipped. Events of one partition key always
 * share a bucket, so they are never published by two workers at the same time.
 *
 * The transaction that holds the lock does nothing but hold it, every event is published in a
 * transaction of its own which deletes the row first and commits after the publishers are done: a
 * batch is not a unit of work, so a failure on the hundredth event costs one redelivery, not ninety
 * nine. A failed event stays in the table with one more attempt counted and a backoff before the
 * next one (see [OutboxRetryPolicy]), and until that backoff runs out its whole stream waits with
 * it — nothing is published out of order with its partition, the other streams of the bucket
 * continue. A worker therefore uses two connections while it publishes a bucket, plus a short
 * third one every [depthInterval] for the backlog gauge, the pool has to have room for them.
 *
 * Every poll of a bucket and every depth measurement runs inside a span of the outbox telemetry
 * ([POLL_SPAN], [DEPTH_SPAN]), so their statements and commits make one trace instead of a root
 * trace each; an application that does not want idle polling traced drops these roots in its
 * sampler. Every published event gets a trace of its own linked to the trace that raised it
 * ([PUBLISH_SPAN], through [com.turbomates.event.Telemetry.link]), with its delete and commit inside.
 *
 * @param outbox the same outbox the interceptor of this application writes to, it carries the
 * buckets, the batch limit, the bucket lock and the serialization of the rows.
 * @param delay pause between two sweeps.
 * @param depthInterval how often [OutboxMetrics.outboxDepth] is measured. Its own ticker, not a part
 * of the sweep: a sweep drowning in a deep backlog can take tens of seconds, and that is exactly
 * when the gauge has to stay fresh.
 * @param errorHandler called with every [FailedEvent] and the failure it comes from. The publisher
 * logs it and defers the row on its own, this only adds what the application does on top — a report
 * carrying the event, an alert — and can not change what happens to the row. A handler that throws
 * is logged and ignored. Failures that are not about one event (a bucket, a sweep) are logged and
 * reported through [OutboxMetrics], there is nothing to hand over there but the exception.
 */
class OutboxPublisher(
    private val database: Database,
    private val publishers: List<Publisher>,
    private val outbox: Outbox,
    private val delay: Duration = Duration.parse("1s"),
    private val depthInterval: Duration = Duration.parse("10s"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val metrics: OutboxMetrics = NoOpOutboxMetrics,
    private val errorHandler: (FailedEvent, Throwable) -> Unit = { _, _ -> }
) : CoroutineScope by CoroutineScope(dispatcher) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun start(): Job {
        return launch {
            launch {
                while (isActive) {
                    try {
                        val depth = outbox.telemetryService.span(DEPTH_SPAN) {
                            suspendTransaction(database) { outbox.depth() }
                        }
                        metrics.outboxDepth(depth)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (ignore: Throwable) {
                        logger.error("error while measuring the outbox depth", ignore)
                    }
                    delay(depthInterval)
                }
            }
            while (isActive) {
                try {
                    sweep()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (ignore: Throwable) {
                    logger.error("error while publishing events", ignore)
                } finally {
                    delay(delay)
                }
            }
        }
    }

    private suspend fun sweep() {
        val buckets = outbox.nextSweep()
        var owned = 0
        for (bucket in buckets) {
            try {
                if (publish(bucket)) {
                    owned++
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (ignore: Throwable) {
                metrics.bucketFailed(bucket, ignore)
                logger.error("error while publishing events of bucket $bucket", ignore)
            }
        }
        metrics.sweepCompleted(owned, buckets.size)
    }

    /**
     * Holds the bucket for the whole batch: this transaction takes the lock, reads the batch and then
     * only waits, the events are published in transactions of their own, so nothing that happens to
     * one of them can undo the others. The lock lives until this transaction ends.
     *
     * The span is opened outside the transaction, so the commit that releases the lock is inside it.
     *
     * @return true when this worker owned the bucket, false when it is held by another one.
     */
    private suspend fun publish(bucket: Int): Boolean =
        outbox.telemetryService.span(POLL_SPAN, mapOf(BUCKET_ATTRIBUTE to bucket.toString())) {
            publishLocked(bucket)
        }

    private suspend fun publishLocked(bucket: Int): Boolean = suspendTransaction(database) {
        if (!outbox.tryLock(this, bucket)) {
            metrics.bucketSkipped(bucket)
            return@suspendTransaction false
        }
        val batch = outbox.load(bucket)
        metrics.bucketAcquired(bucket, batch.events.size, batch.lag())
        var published = 0
        var failed = 0
        val blocked = mutableSetOf<UUID>()
        for (event in batch.events) {
            if (event.partitionKey in blocked) {
                continue
            }
            try {
                val original = event.event
                    ?: throw IllegalStateException("event ${event.id} can not be read back", event.error)
                if (publish(event, original)) {
                    published++
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (ignore: Throwable) {
                failed++
                blocked.add(event.partitionKey)
                defer(bucket, event, ignore)
                logger.error("error while publishing event ${event.id}", ignore)
            }
        }
        metrics.bucketPublished(bucket, published, failed)
        true
    }

    /**
     * Publishes one event in a transaction of its own, which deletes the row before the publishers
     * run: a publisher that throws rolls that deletion back and leaves the event for the next sweep,
     * and the row lock keeps a worker that lost the bucket from publishing the same event twice.
     *
     * The link is opened outside the transaction, so the delete and the commit are inside it. The
     * publishers still get the trace stored with the event, not the one of the link: the consumer
     * links to the trace that raised the event.
     *
     * @return false when the row is already gone.
     */
    private suspend fun publish(event: Outbox.OutboxBatch.Item, original: Event): Boolean {
        var published = false
        val attributes = mapOf(EVENT_ID_ATTRIBUTE to event.id.toString(), EVENT_NAME_ATTRIBUTE to original.key.name)
        outbox.telemetryService.link(event.traceInformation ?: NO_TRACE, PUBLISH_SPAN, attributes) {
            published = publishDeleting(event, original)
        }
        return published
    }

    private suspend fun publishDeleting(event: Outbox.OutboxBatch.Item, original: Event): Boolean =
        inTopLevelSuspendTransaction(database, null, null, null) {
            if (!outbox.delete(event.id)) {
                return@inTopLevelSuspendTransaction false
            }
            publishers.forEach { publisher ->
                logger.debug(
                    "event " + event.id.toString() +
                        " was published by ${publisher.javaClass.name} in worker"
                )
                publisher.publish(original, event.traceInformation)
            }
            true
        }

    /**
     * Counts the failed publish in a transaction of its own — the publishing transaction has rolled
     * its deletion back, this one has to survive it. The rest of the stream is kept back by
     * [Outbox.load] until the backoff runs out.
     */
    private suspend fun defer(bucket: Int, event: Outbox.OutboxBatch.Item, error: Throwable) {
        inTopLevelSuspendTransaction(database, null, null, null) {
            outbox.failed(event.id, event.attempts)
        }
        val attempts = event.attempts + 1
        metrics.eventFailed(bucket, event.id, attempts, error)
        report(
            FailedEvent(
                bucket = bucket,
                id = event.id,
                partitionKey = event.partitionKey,
                attempts = attempts,
                event = event.event,
                payload = event.payload,
                traceInformation = event.traceInformation
            ),
            error
        )
    }

    /**
     * The `errorHandler` of the application decides nothing — the row is deferred by the time it is
     * called — so a handler that throws must not take the publisher with it: it would kill the sweep
     * loop over a broken report of a failure that is already handled.
     */
    private fun report(failed: FailedEvent, error: Throwable) {
        try {
            errorHandler(failed, error)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ignore: Throwable) {
            logger.error("outbox error handler of event ${failed.id} threw", ignore)
        }
    }

    companion object {
        /** Span of one poll of a bucket: the lock, the read of the batch and the commit. */
        const val POLL_SPAN = "outbox poll"

        /** Span of one measurement of the backlog gauge. */
        const val DEPTH_SPAN = "outbox depth"

        /** Root span of one published event, linked to the trace that raised it. */
        const val PUBLISH_SPAN = "outbox publish"

        const val BUCKET_ATTRIBUTE = "outbox.bucket"
        const val EVENT_ID_ATTRIBUTE = "outbox.event.id"
        const val EVENT_NAME_ATTRIBUTE = "outbox.event.name"

        private val NO_TRACE = TraceInformation(null, null, null)
    }
}
