package com.turbomates.event.telemetry.opentelemetry

import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class OpenTelemetry : Telemetry {
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
        span.runCurrent { block.invoke(traceInformation()) }
    }

    override suspend fun <T> span(
        spanName: String,
        attributes: Map<String, String>,
        block: suspend () -> T
    ): T {
        val span = GlobalOpenTelemetry.get().getTracer("com.turbomates.event")
            .spanBuilder(spanName)
            .setParent(Context.current())
            .startSpan()
        attributes.forEach { (key, value) -> span.setAttribute(key, value) }
        return span.runCurrent(block)
    }

    /**
     * Runs [block] with this span current and ends the span after it. The context travels with the
     * coroutine: a scope opened with `makeCurrent` would stay on the thread the block suspended on
     * and be missing on the one it resumes on. The failure crosses `withContext` as a value, thrown
     * through it the coroutine debug mode would replace it with a copy.
     */
    private suspend fun <T> Span.runCurrent(block: suspend () -> T): T =
        try {
            withContext(Context.current().with(this).asContextElement()) { runCatching { block() } }.getOrThrow()
        } catch (failure: Throwable) {
            // a cancellation while the caller is still active, a timeout inside the block, is a failure
            if (failure !is CancellationException || currentCoroutineContext().isActive) {
                setStatus(StatusCode.ERROR, failure.message.orEmpty())
                recordException(failure)
            }
            throw failure
        } finally {
            end()
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
