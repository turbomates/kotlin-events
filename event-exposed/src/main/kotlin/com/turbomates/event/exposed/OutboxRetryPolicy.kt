package com.turbomates.event.exposed

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Backoff of an event the publishers keep failing: after attempt N the row waits
 * `initialDelay * multiplier^(N-1)`, capped at [maxDelay], before it is tried again. While it
 * waits, its whole stream waits with it (see [Outbox.load]), so a poison event slows its own
 * partition down instead of hammering the log every sweep — and nothing overtakes it.
 *
 * There is no attempt limit on purpose: the outbox never gives an event up, because giving it up
 * would silently break the order of its stream. A poison event is a bug to fix — once the fix is
 * deployed the event publishes on the next try and the stream drains itself. The truly unreadable
 * row is removed by hand, `DELETE FROM outbox_events WHERE id = ...`, which is a decision the
 * application makes, not the library. [OutboxMetrics.eventFailed] is the signal to alert on.
 */
class OutboxRetryPolicy(
    private val initialDelay: Duration = 1.seconds,
    private val multiplier: Double = 2.0,
    private val maxDelay: Duration = 5.minutes
) {
    init {
        require(initialDelay.isPositive()) { "initial delay must be positive, got $initialDelay" }
        require(multiplier >= 1.0) { "multiplier must be at least 1, got $multiplier" }
        require(maxDelay >= initialDelay) { "max delay $maxDelay must not be under the initial delay $initialDelay" }
    }

    /** Delay before the next try of an event that failed [attempts] times, the first failure is 1. */
    fun delay(attempts: Int): Duration {
        require(attempts > 0) { "attempts must be positive, got $attempts" }
        val exponential = initialDelay.inWholeMilliseconds.toDouble() * multiplier.pow(attempts - 1)
        if (!exponential.isFinite() || exponential >= maxDelay.inWholeMilliseconds) {
            return maxDelay
        }
        return exponential.toLong().milliseconds
    }
}
