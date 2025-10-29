package com.turbomates.event.exposed

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transactionScope

/**
 * Represents telemetry context (trace and span information)
 * that should be propagated with events
 */
data class TelemetryContext(
    val traceId: String?,
    val spanId: String?
) {
    companion object {
        val EMPTY = TelemetryContext(null, null)
    }
}

/**
 * Interface for extracting current telemetry context from different
 * telemetry implementations (OpenTelemetry, Micrometer, etc.)
 */
interface TelemetryContextProvider {
    /**
     * Extract current trace and span IDs from the active context
     * @return TelemetryContext with trace/span IDs or EMPTY if no active trace
     */
    fun getCurrentContext(): TelemetryContext
}

/**
 * Default implementation that returns empty context
 * Used when telemetry is not configured
 */
object NoOpTelemetryContextProvider : TelemetryContextProvider {
    override fun getCurrentContext(): TelemetryContext = TelemetryContext.EMPTY
}

/**
 * Extension property to access telemetry context provider for a transaction.
 * Uses Exposed's transactionScope to store provider per transaction (thread-safe).
 *
 * Example usage:
 * ```kotlin
 * transaction {
 *     telemetryContextProvider = OpenTelemetryContextProvider()
 *     events.addEvent(MyEvent()) // Will use the provider set above
 * }
 * ```
 */
var Transaction.telemetryContextProvider: TelemetryContextProvider by transactionScope {
    NoOpTelemetryContextProvider
}
