package com.turbomates.event.exposed

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
 * Global holder for telemetry context provider
 * Can be set during application initialization
 */
object TelemetryContextHolder {
    @Volatile
    var provider: TelemetryContextProvider = NoOpTelemetryContextProvider
}
