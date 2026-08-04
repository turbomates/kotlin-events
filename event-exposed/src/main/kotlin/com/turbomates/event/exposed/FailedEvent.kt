package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.TraceInformation
import java.util.UUID

/**
 * An event the publishers failed on, handed to the `errorHandler` of [OutboxPublisher] together with
 * the failure. The attempt is already counted and the stream of the event already waits out its
 * backoff by the time the handler is called: it observes, it decides nothing (see [OutboxRetryPolicy]).
 *
 * This is the only thing the outbox reports that [OutboxMetrics] can not carry — a metric is a number
 * and a bounded label, and the event is neither. Everything else the publisher catches is a number:
 * a bucket it could not take or a sweep that did not reach the database is logged and counted by
 * [OutboxMetrics.bucketFailed], there is nothing to hand over but the exception.
 *
 * @param attempts attempts made including this one, the first failure is 1. It keeps growing while
 * nobody looks, which is what makes a poison event visible.
 * @param event null when the row can not be decoded — an unknown type mid-rollout, a serializer that
 * lost a field. That is exactly when [payload] is the only thing there is of the event, and the
 * decoding failure is the error the handler is called with.
 * @param payload the row as it is stored, the json of the `event` column.
 */
class FailedEvent(
    val bucket: Int,
    val id: UUID,
    val partitionKey: UUID,
    val attempts: Int,
    val event: Event?,
    val payload: String,
    val traceInformation: TraceInformation?
)
