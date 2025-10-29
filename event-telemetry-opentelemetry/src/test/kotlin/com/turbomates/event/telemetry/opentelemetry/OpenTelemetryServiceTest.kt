package com.turbomates.event.telemetry.opentelemetry

import com.turbomates.event.TraceInformation
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTelemetryServiceTest {
    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var service: OpenTelemetryService

    @BeforeTest
    fun setup() {
        // Reset GlobalOpenTelemetry
        GlobalOpenTelemetry.resetForTest()

        // Setup in-memory span exporter for testing
        spanExporter = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()

        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()
            ))
            .buildAndRegisterGlobal()

        service = OpenTelemetryService()
    }

    @AfterTest
    fun teardown() {
        spanExporter.reset()
        GlobalOpenTelemetry.resetForTest()
    }

    @Test
    fun `traceInformation should return empty trace when no active span`() {
        val traceInfo = service.traceInformation()

        assertNull(traceInfo.traceparent, "traceparent should be null when no active span")
        assertNull(traceInfo.tracestate, "tracestate should be null when no active span")
        assertNull(traceInfo.baggage, "baggage should be null when no active span")
    }

    @Test
    fun `traceInformation should capture current trace context`() {
        val tracer = GlobalOpenTelemetry.get().getTracer("test")
        val span = tracer.spanBuilder("test-span").startSpan()

        try {
            span.makeCurrent().use {
                val traceInfo = service.traceInformation()

                assertNotNull(traceInfo.traceparent, "traceparent should be captured from active span")
                assertTrue(
                    traceInfo.traceparent!!.startsWith("00-"),
                    "traceparent should follow W3C format"
                )
            }
        } finally {
            span.end()
        }
    }

    @Test
    fun `link should create child span with parent context`() = runBlocking {
        // Create parent span
        val tracer = GlobalOpenTelemetry.get().getTracer("test")
        val parentSpan = tracer.spanBuilder("parent-span").startSpan()

        try {
            val traceInfo = parentSpan.makeCurrent().use {
                service.traceInformation()
            }

            // Create child span via link
            service.link(
                traceInformation = traceInfo,
                spanName = "child-span",
                attributes = mapOf("test.key" to "test.value")
            ) {
                // Block execution
            }
        } finally {
            parentSpan.end()
        }

        // Verify spans were created
        val finishedSpans = spanExporter.finishedSpanItems
        assertEquals(2, finishedSpans.size, "Should have parent and child spans")

        val parentSpanData = finishedSpans.first { it.name == "parent-span" }
        val childSpanData = finishedSpans.first { it.name == "child-span" }

        // Verify child span has correct parent
        assertEquals(
            parentSpanData.traceId,
            childSpanData.traceId,
            "Child span should have same trace ID as parent"
        )
        assertEquals(
            parentSpanData.spanId,
            childSpanData.parentSpanId,
            "Child span should have parent span ID"
        )

        // Verify attributes
        assertEquals(
            "test.value",
            childSpanData.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("test.key")),
            "Child span should have custom attributes"
        )
    }

    @Test
    fun `link should create span with multiple attributes`() = runBlocking {
        val traceInfo = TraceInformation(null, null, null)

        service.link(
            traceInformation = traceInfo,
            spanName = "multi-attr-span",
            attributes = mapOf(
                "attr1" to "value1",
                "attr2" to "value2",
                "attr3" to "value3"
            )
        ) {
            // Block execution
        }

        val finishedSpans = spanExporter.finishedSpanItems
        assertEquals(1, finishedSpans.size, "Should have one span")

        val spanData = finishedSpans.first()
        assertEquals("multi-attr-span", spanData.name)
        assertEquals(
            "value1",
            spanData.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("attr1"))
        )
        assertEquals(
            "value2",
            spanData.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("attr2"))
        )
        assertEquals(
            "value3",
            spanData.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("attr3"))
        )
    }

    @Test
    fun `link should propagate exceptions and still end span`() = runBlocking {
        val traceInfo = TraceInformation(null, null, null)
        val expectedException = RuntimeException("Test exception")

        try {
            service.link(
                traceInformation = traceInfo,
                spanName = "error-span",
                attributes = emptyMap()
            ) {
                throw expectedException
            }
            kotlin.test.fail("Should have thrown exception")
        } catch (e: RuntimeException) {
            assertEquals(expectedException, e, "Should propagate the same exception")
        }

        // Verify span was still ended
        val finishedSpans = spanExporter.finishedSpanItems
        assertEquals(1, finishedSpans.size, "Span should be ended even on exception")
        assertEquals("error-span", finishedSpans.first().name)
    }

    @Test
    fun `link should work with empty attributes`() = runBlocking {
        val traceInfo = TraceInformation(null, null, null)

        service.link(
            traceInformation = traceInfo,
            spanName = "no-attr-span",
            attributes = emptyMap()
        ) {
            // Block execution
        }

        val finishedSpans = spanExporter.finishedSpanItems
        assertEquals(1, finishedSpans.size, "Should have one span")
        assertEquals("no-attr-span", finishedSpans.first().name)
    }

    @Test
    fun `link should create nested spans correctly`() = runBlocking {
        val traceInfo = TraceInformation(null, null, null)

        service.link(
            traceInformation = traceInfo,
            spanName = "outer-span"
        ) {
            service.link(
                traceInformation = this,
                spanName = "inner-span"
            ) {
                // Nested block execution
            }
        }

        val finishedSpans = spanExporter.finishedSpanItems
        assertEquals(2, finishedSpans.size, "Should have two nested spans")

        val outerSpan = finishedSpans.first { it.name == "outer-span" }
        val innerSpan = finishedSpans.first { it.name == "inner-span" }

        assertEquals(
            outerSpan.traceId,
            innerSpan.traceId,
            "Nested spans should share trace ID"
        )
        assertEquals(
            outerSpan.spanId,
            innerSpan.parentSpanId,
            "Inner span should have outer span as parent"
        )
    }

    @Test
    fun `link should preserve trace information with all W3C headers`() = runBlocking {
        val traceInfo = TraceInformation(
            traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
            tracestate = "congo=t61rcWkgMzE",
            baggage = "userId=alice,serverNode=DF:28"
        )

        service.link(
            traceInformation = traceInfo,
            spanName = "trace-preserved-span"
        ) {
            // Verify trace ID is preserved (first part of traceparent)
            assertNotNull(this.traceparent)
            assertTrue(
                this.traceparent!!.startsWith("00-0af7651916cd43dd8448eb211c80319c-"),
                "Trace ID should be preserved in traceparent"
            )
            // Note: span ID (second part) will be different because a new child span was created
        }

        val finishedSpans = spanExporter.finishedSpanItems
        assertEquals(1, finishedSpans.size, "Should have one span")

        // Verify the span has the correct trace ID
        val spanData = finishedSpans.first()
        assertEquals("0af7651916cd43dd8448eb211c80319c", spanData.traceId)
    }
}