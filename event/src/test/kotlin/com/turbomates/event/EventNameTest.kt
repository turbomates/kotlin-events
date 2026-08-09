package com.turbomates.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

@Suppress("DEPRECATION")
class EventNameTest {
    @Test
    fun `a key that declares nothing keeps the name derived from its class`() {
        assertEquals("turbomates.event.without_name", WithoutName.name)
        assertEquals(WithoutName.derivedName(), WithoutName.name)
        assertFalse(WithoutName.hasDeclaredName())
    }

    @Test
    fun `a declared name replaces the derived one`() {
        assertEquals("event.name.declared", WithName.name)
        assertTrue(WithName.hasDeclaredName())
    }

    @Test
    fun `an anonymous key has no name to derive`() {
        val key = object : Event.Key<WithName> {
            override val name = "event.name.anonymous"
        }
        assertTrue(key.hasDeclaredName())
    }
}

@Serializable
private data class WithoutName(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<WithoutName>
}

@Serializable
private data class WithName(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<WithName> {
        override val name = "event.name.declared"
    }
}
