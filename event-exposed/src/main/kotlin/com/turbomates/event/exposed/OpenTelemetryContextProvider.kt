package com.turbomates.event.exposed

/**
 * Example implementation of TelemetryContextProvider for OpenTelemetry.
 *
 * This file is provided as a reference implementation. To use it:
 *
 * 1. Add OpenTelemetry dependencies to your build.gradle.kts:
 *    ```kotlin
 *    implementation("io.opentelemetry:opentelemetry-api:1.32.0")
 *    ```
 *
 * 2. Uncomment the code below
 *
 * 3. Set once at application startup:
 *    ```kotlin
 *    fun main() {
 *        // Set default provider once
 *        TelemetryConfig.setDefaultProvider(OpenTelemetryContextProvider())
 *
 *        // Now all transactions automatically capture telemetry
 *        transaction {
 *            events.addEvent(MyEvent()) // trace/span captured automatically
 *        }
 *    }
 *    ```
 *
 * Example usage:
 * ```kotlin
 * import io.opentelemetry.api.trace.Span
 * import io.opentelemetry.api.trace.SpanContext
 *
 * class OpenTelemetryContextProvider : TelemetryContextProvider {
 *     override fun getCurrentContext(): TelemetryContext {
 *         val span = Span.current()
 *         val spanContext: SpanContext = span.spanContext
 *
 *         return if (spanContext.isValid) {
 *             TelemetryContext(
 *                 traceId = spanContext.traceId,
 *                 spanId = spanContext.spanId
 *             )
 *         } else {
 *             TelemetryContext.EMPTY
 *         }
 *     }
 * }
 * ```
 *
 * For Micrometer Tracing, use:
 * ```kotlin
 * import io.micrometer.tracing.Tracer
 *
 * class MicrometerContextProvider(private val tracer: Tracer) : TelemetryContextProvider {
 *     override fun getCurrentContext(): TelemetryContext {
 *         val span = tracer.currentSpan()
 *         return if (span != null) {
 *             TelemetryContext(
 *                 traceId = span.context().traceId(),
 *                 spanId = span.context().spanId()
 *             )
 *         } else {
 *             TelemetryContext.EMPTY
 *         }
 *     }
 * }
 * ```
 *
 * Example with Spring Boot:
 * ```kotlin
 * @Configuration
 * class TelemetryConfiguration {
 *     @PostConstruct
 *     fun setup() {
 *         TelemetryConfig.setDefaultProvider(OpenTelemetryContextProvider())
 *     }
 * }
 *
 * @Service
 * class OrderService {
 *     fun createOrder(order: Order) {
 *         // Telemetry works automatically - no setup needed!
 *         transaction {
 *             OrderTable.insert { ... }
 *             events.addEvent(OrderCreatedEvent(order.id))
 *         }
 *     }
 * }
 * ```
 */
