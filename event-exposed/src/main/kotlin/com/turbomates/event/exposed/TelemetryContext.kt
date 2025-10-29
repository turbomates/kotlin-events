package com.turbomates.event.exposed

import org.jetbrains.exposed.sql.Database
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
 * Internal storage for Database-specific telemetry providers.
 * Uses WeakHashMap to prevent memory leaks when Database instances are garbage collected.
 */
private val databaseProviders = java.util.WeakHashMap<Database, TelemetryContextProvider>()

/**
 * Configure telemetry provider for a specific Database instance.
 * This provider will be used for all transactions on this database.
 *
 * Example:
 * ```kotlin
 * val database = Database.connect(...)
 * database.telemetryProvider = OpenTelemetryContextProvider()
 *
 * // Now all transactions on this database use the provider automatically
 * transaction(database) {
 *     events.addEvent(MyEvent()) // telemetry captured automatically
 * }
 * ```
 */
var Database.telemetryProvider: TelemetryContextProvider
    get() = synchronized(databaseProviders) {
        databaseProviders[this] ?: NoOpTelemetryContextProvider
    }
    set(value) = synchronized(databaseProviders) {
        databaseProviders[this] = value
    }

/**
 * Extension property to access telemetry context provider for a transaction.
 * Uses Exposed's transactionScope to store provider per transaction (thread-safe).
 *
 * By default, uses the provider configured for the Database instance.
 * Can be overridden for specific transactions (useful for testing).
 *
 * Example usage:
 * ```kotlin
 * // Configure once per Database
 * val database = Database.connect(...)
 * database.telemetryProvider = OpenTelemetryContextProvider()
 *
 * // All transactions automatically use the database's provider
 * transaction(database) {
 *     events.addEvent(MyEvent()) // telemetry captured automatically
 * }
 *
 * // Override for specific transaction (e.g., in tests)
 * transaction(database) {
 *     telemetryContextProvider = mockProvider
 *     events.addEvent(TestEvent())
 * }
 * ```
 */
var Transaction.telemetryContextProvider: TelemetryContextProvider by transactionScope {
    db.telemetryProvider
}
