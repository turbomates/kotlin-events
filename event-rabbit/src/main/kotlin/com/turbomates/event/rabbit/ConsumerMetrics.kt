package com.turbomates.event.rabbit

import kotlin.time.Duration

/**
 * Observability of the consumer side, for the application to wire into its own metrics registry.
 * The publisher of an event is measured by the outbox, this is the other end: what a queue actually
 * did with the delivery — how long it waited for a free worker ([handled] `waited`), how long the
 * subscriber ran ([handled] `took`), and every outcome other than a successful handled ack ([failed],
 * [retried], [parked], [requeued], [noSubscriber]).
 *
 * The two signals nothing else reports are [handled]'s `waited` and [parked]. `waited` is the delay
 * the consumer adds by itself: the broker hands over up to `prefetchCount` messages and they queue
 * in memory until one of `maxConcurrency` workers is free, which the queue depth of the broker can
 * not show — those messages count as unacked there, so the queue looks empty while the backlog sits
 * in this process. [parked] is the message the retries gave up on — it is in the parking lot queue
 * and nothing will take it out but a human.
 *
 * Every method has an empty default, so an implementation only overrides what it exports. The
 * callback logs its own errors and calls `errorHandler`, this interface is not a logging hook.
 *
 * Implementations must not throw: [handled] is called between the subscriber and the ack of the
 * delivery, so an exception there costs a redelivery of a message that was already processed.
 */
interface ConsumerMetrics {
    /**
     * The subscriber returned and the delivery was acked.
     *
     * @param waited time between the arrival of the delivery and the moment a worker took it — the
     * queue is starved of workers when this grows, not slow at processing.
     * @param took time the subscriber itself ran, decoding included.
     */
    fun handled(queue: String, routingKey: String, waited: Duration, took: Duration) {}

    /**
     * The delivery was acked without being processed: this consumer has no subscriber for the key
     * of the event. A binding routes messages nobody handles — they are dropped silently otherwise.
     */
    fun noSubscriber(queue: String, routingKey: String) {}

    /**
     * Processing threw — the subscriber, or the decoding of the body. [retried], [parked] or
     * [requeued] follows and tells what happened to the delivery.
     *
     * @param retries how many times this message was dead-lettered before this attempt, 0 on the
     * first one.
     */
    fun failed(queue: String, routingKey: String, retries: Long, took: Duration, error: Throwable) {}

    /** The failed delivery was rejected to the retry queue and comes back after `retryDelay`. */
    fun retried(queue: String, routingKey: String, retries: Long) {}

    /**
     * The failed delivery ran out of `maxRetries` and was published to the parking lot queue. It is
     * out of the pipeline for good until somebody handles it by hand — alert on this one.
     */
    fun parked(queue: String, routingKey: String, retries: Long) {}

    /**
     * The failed delivery was nacked back into its own queue, because the queue has no retries
     * configured (`maxRetries == 0`). It is redelivered immediately and as often as it keeps
     * failing.
     */
    fun requeued(queue: String, routingKey: String) {}
}

object NoOpConsumerMetrics : ConsumerMetrics
