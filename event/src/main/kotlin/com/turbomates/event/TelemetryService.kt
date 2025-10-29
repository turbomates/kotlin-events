package com.turbomates.event

interface TelemetryService {
    val traceparent: String?
    val spanId: String?

    companion object {
        const val TRACEPARENT_KEY = "traceparent"
        const val SPAN_ID_KEY = "spanId"
    }
}

class NoOpTelemetryService : TelemetryService {
    override val traceparent: String? = null
    override val spanId: String? = null
}
