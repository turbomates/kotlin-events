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
