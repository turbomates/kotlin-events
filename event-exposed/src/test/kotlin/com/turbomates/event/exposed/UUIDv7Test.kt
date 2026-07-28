package com.turbomates.event.exposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UUIDv7Test {
    @Test
    fun `carries the version and variant of the RFC`() {
        val id = UUIDv7.randomUUID()

        assertEquals(7, id.version())
        assertEquals(2, id.variant())
    }

    @Test
    fun `ids of later moments are bigger`() {
        val first = UUIDv7.randomUUID()
        Thread.sleep(2)
        val second = UUIDv7.randomUUID()

        assertTrue(first < second, "expected $first < $second")
    }

    @Test
    fun `ids stay unique within one millisecond`() {
        val ids = (1..10_000).map { UUIDv7.randomUUID() }

        assertEquals(ids.size, ids.toSet().size)
    }
}
