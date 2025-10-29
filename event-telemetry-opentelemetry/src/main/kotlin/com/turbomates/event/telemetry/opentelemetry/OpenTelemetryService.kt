package com.turbomates.event.telemetry.opentelemetry

import com.turbomates.event.TelemetryService
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context

class OpenTelemetryService : TelemetryService {
    override val traceparent: String?
        get() {
            val map = mutableMapOf<String, String>()
            W3CTraceContextPropagator.getInstance().inject(Context.current(), map) { m, k, v -> m?.put(k, v) }
            return map[TelemetryService.TRACEPARENT_KEY]
        }
    override val spanId: String?
        get() = Span.current().spanContext.spanId
}
