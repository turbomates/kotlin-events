package com.turbomates.event.telemetry.opentelemetry

import com.turbomates.event.TelemetryService
import com.turbomates.event.TraceInformation
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter

class OpenTelemetryService : TelemetryService {
    private val traceparent: String?
        get() = currentHeader("traceparent")

    private val tracestate: String?
        get() = currentHeader("tracestate")

    private val baggage: String?
        get() = currentHeader("baggage")

    override fun traceInformation(): TraceInformation {
        return TraceInformation(traceparent, tracestate, baggage)
    }

    override suspend fun link(
        traceInformation: TraceInformation,
        spanName: String,
        attributes: Map<String, String>,
        block: suspend TraceInformation.() -> Unit
    ) {
        val otel = GlobalOpenTelemetry.get()
        val tracer = otel.getTracer("com.turbomates.event")

        val parentCtx = traceInformation.extractParentContext()

        val span = tracer.spanBuilder(spanName)
            .setParent(parentCtx)
            .startSpan()
        attributes.forEach { (k, v) ->
            span.setAttribute(k, v)
        }
        try {
            span.makeCurrent().use {
                block.invoke(TraceInformation(traceparent, tracestate, baggage))
            }
        } finally {
            span.end()
        }
    }

    private fun TraceInformation.extractParentContext(): Context {
        val propagator = GlobalOpenTelemetry.get().propagators.textMapPropagator
        return propagator.extract(Context.current(), asHeaders(), HeadersMapGetter)
    }

    private fun TraceInformation.asHeaders(): Map<String, String> = buildMap {
        traceparent?.let { put("traceparent", it) }
        tracestate?.let { put("tracestate", it) }
        baggage?.let { put("baggage", it) }
    }

    private fun currentHeader(key: String): String? {
        val map = mutableMapOf<String, String>()
        GlobalOpenTelemetry.get().propagators.textMapPropagator
            .inject(Context.current(), map) { m, k, v -> m?.put(k, v) }
        return map[key]
    }

    private object HeadersMapGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }
}
