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
 * Global configuration for telemetry.
 * Set the default provider once during application startup.
 *
 * Example:
 * ```kotlin
 * fun main() {
 *     // Initialize telemetry
 *     TelemetryConfig.setDefaultProvider(OpenTelemetryContextProvider())
 *
 *     // Now all transactions will automatically use this provider
 *     transaction {
 *         events.addEvent(MyEvent()) // trace/span captured automatically
 *     }
 * }
 * ```
 */
object TelemetryConfig {
    @Volatile
    private var _defaultProvider: TelemetryContextProvider = NoOpTelemetryContextProvider

    /**
     * Get the default telemetry provider.
     * Returns NoOpTelemetryContextProvider if not set.
     */
    val defaultProvider: TelemetryContextProvider
        get() = _defaultProvider

    /**
     * Set the default telemetry provider for the application.
     * This should be called once during application startup.
     *
     * @param provider The telemetry context provider to use globally
     */
    fun setDefaultProvider(provider: TelemetryContextProvider) {
        _defaultProvider = provider
    }
}

/**
 * Extension property to access telemetry context provider for a transaction.
 * Uses Exposed's transactionScope to store provider per transaction (thread-safe).
 *
 * By default, uses TelemetryConfig.defaultProvider which can be set once at startup.
 * Can be overridden for specific transactions (useful for testing).
 *
 * Example usage:
 * ```kotlin
 * // Set once at startup
 * TelemetryConfig.setDefaultProvider(OpenTelemetryContextProvider())
 *
 * // All transactions automatically use the default provider
 * transaction {
 *     events.addEvent(MyEvent()) // trace/span captured automatically
 * }
 *
 * // Override for specific transaction (e.g., in tests)
 * transaction {
 *     telemetryContextProvider = mockProvider
 *     events.addEvent(TestEvent())
 * }
 * ```
 */
var Transaction.telemetryContextProvider: TelemetryContextProvider by transactionScope {
    TelemetryConfig.defaultProvider
}
