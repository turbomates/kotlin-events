package com.turbomates.event

import kotlinx.serialization.Serializable

interface Telemetry {
    fun traceInformation(): TraceInformation
    suspend fun link(
        traceInformation: TraceInformation,
        spanName: String,
        attributes: Map<String, String> = emptyMap(),
        block: suspend TraceInformation.() -> Unit
    )

    /**
     * Runs [block] inside a span of the current trace, or in a new trace when there is none. The
     * outbox publishers open one around every poll, so the statements and the commit of a poll
     * land in one trace instead of a root trace each. Defaults to running [block] alone, which
     * keeps implementations written before this method working unchanged.
     */
    suspend fun <T> span(
        spanName: String,
        attributes: Map<String, String> = emptyMap(),
        block: suspend () -> T
    ): T = block()
}

class NoOpTelemetry : Telemetry {
    override fun traceInformation(): TraceInformation {
        return TraceInformation(null, null, null)
    }

    override suspend fun link(
        traceInformation: TraceInformation,
        spanName: String,
        attributes: Map<String, String>,
        block: suspend TraceInformation.() -> Unit
    ) {
        block.invoke(traceInformation)
    }
}

@Serializable
data class TraceInformation(
    val traceparent: String?,
    val tracestate: String?,
    val baggage: String?,
)
