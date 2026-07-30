package com.turbomates.event.exposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UUIDv7Test {
    @Test
    fun `carries the version and variant of the RFC`() {
        val id = uuidV7()

        assertEquals(7, id.version())
        assertEquals(2, id.variant())
    }

    @Test
    fun `ids grow monotonically`() {
        val ids = (1..10_000).map { uuidV7() }

        assertEquals(ids, ids.sorted(), "ids are strictly growing even within one millisecond")
        assertEquals(ids.size, ids.toSet().size)
    }
}
