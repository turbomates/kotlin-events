package com.turbomates.event.rabbit

import com.rabbitmq.client.impl.LongStringHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The header values below are the shapes the amqp client hands a consumer, not the ones a publisher
 * puts in: a `String` set by the publisher arrives as a `LongString`. Testing the parsing on a map of
 * plain strings would be green on the code that lost every trace in production.
 */
class TraceHeadersTest {
    @Test
    fun `a long string is read as its content`() {
        val headers = mapOf(QueueConfig.TRACEPARENT_HEADER to LongStringHelper.asLongString(TRACEPARENT))

        assertEquals(TRACEPARENT, headers.stringHeader(QueueConfig.TRACEPARENT_HEADER))
    }

    @Test
    fun `a string is read as is`() {
        val headers = mapOf(QueueConfig.TRACEPARENT_HEADER to TRACEPARENT)

        assertEquals(TRACEPARENT, headers.stringHeader(QueueConfig.TRACEPARENT_HEADER))
    }

    @Test
    fun `a byte array is read as utf-8`() {
        val headers = mapOf(QueueConfig.TRACEPARENT_HEADER to TRACEPARENT.toByteArray())

        assertEquals(TRACEPARENT, headers.stringHeader(QueueConfig.TRACEPARENT_HEADER))
    }

    @Test
    fun `an absent header is null`() {
        val headers = mapOf<String, Any?>(QueueConfig.TRACEPARENT_HEADER to TRACEPARENT)

        assertNull(headers.stringHeader(QueueConfig.TRACESTATE_HEADER))
    }

    @Test
    fun `a null header is null and not the string null`() {
        val headers = mapOf<String, Any?>(QueueConfig.TRACESTATE_HEADER to null)

        assertNull(headers.stringHeader(QueueConfig.TRACESTATE_HEADER))
    }

    @Test
    fun `utf-8 in a long string survives`() {
        val baggage = "betforge.reference.type=ставка,betforge.reference.id=42"
        val headers = mapOf(QueueConfig.BAGGAGE_HEADER to LongStringHelper.asLongString(baggage))

        assertEquals(baggage, headers.stringHeader(QueueConfig.BAGGAGE_HEADER))
    }

    private companion object {
        const val TRACEPARENT = "00-9b023cb5d867194bf771f7eaff3ab185-b7ad6b7169203331-01"
    }
}
