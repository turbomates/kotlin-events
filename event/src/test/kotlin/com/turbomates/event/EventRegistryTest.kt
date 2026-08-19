package com.turbomates.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

class EventRegistryTest {
    @Test
    fun `a declared name resolves to the serializer of its event`() {
        val registry = EventRegistry(Declared)
        assertEquals(Declared.serializer().descriptor, registry["registry.test.declared"]?.descriptor)
    }

    @Test
    fun `an event with no declared name is not held`() {
        val registry = EventRegistry()
        assertFalse(registry.register(Derived))
        assertNull(registry["registry.test.derived"])
    }

    @Test
    fun `registering the same event twice is allowed`() {
        val registry = EventRegistry(Declared)
        assertTrue(registry.register(Declared))
    }

    @Test
    fun `two events cannot answer one name`() {
        val registry = EventRegistry(Declared)
        val failure = assertFailsWith<IllegalArgumentException> { registry.register(Colliding) }
        assertEquals(true, failure.message?.contains("registry.test.declared"))
    }

    @Test
    fun `a name of one segment is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> { EventRegistry(Flat) }
        assertEquals(true, failure.message?.contains("flat"))
    }

    @Test
    fun `a name that is not snake case is refused`() {
        assertFailsWith<IllegalArgumentException> { EventRegistry(Camel) }
    }

    @Test
    fun `a declared name answering the derived route of another event is refused by the constructor`() {
        // Derived is routed as 'turbomates.event.derived' without declaring anything; a queue bound
        // to that route would receive DerivedClash too. The claim is made by every registration
        // path, the constructor included.
        val failure = assertFailsWith<IllegalArgumentException> { EventRegistry(Derived, DerivedClash) }
        assertEquals(true, failure.message?.contains("turbomates.event.derived"))
    }

    @Test
    fun `two events sharing a derived route are refused by the constructor`() {
        // Standalone keys of one package derive their route from the package alone, so these two
        // collide without declaring anything at all.
        val failure = assertFailsWith<IllegalArgumentException> { EventRegistry(ClashKeyA, ClashKeyB) }
        assertEquals(true, failure.message?.contains("are both routed as"))
    }

    @Test
    fun `a key that is not the companion of its event needs its serializer`() {
        val failure = assertFailsWith<IllegalArgumentException> { EventRegistry(StandaloneKey) }
        assertEquals(true, failure.message?.contains("registry.test.standalone"))
        assertTrue(EventRegistry().register(StandaloneKey, Standalone.serializer()))
    }
}

@Serializable
private data class Declared(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<Declared> {
        override val name = "registry.test.declared"
    }
}

@Serializable
private data class Colliding(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<Colliding> {
        override val name = "registry.test.declared"
    }
}

@Serializable
private data class Derived(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<Derived>
}

@Serializable
private data class Flat(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<Flat> {
        override val name = "flat"
    }
}

@Serializable
private data class Camel(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<Camel> {
        override val name = "registry.test.NotSnakeCase"
    }
}

@Serializable
private data class Standalone(val value: String = "") : Event() {
    override val key: Key<out Event> = StandaloneKey
}

@Serializable
private data class DerivedClash(val value: String = "") : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<DerivedClash> {
        override val name = "turbomates.event.derived"
    }
}

@Serializable
private data class ClashA(val value: String = "") : Event() {
    override val key: Key<out Event> = ClashKeyA
}

@Serializable
private data class ClashB(val value: String = "") : Event() {
    override val key: Key<out Event> = ClashKeyB
}

private object ClashKeyA : Event.Key<ClashA>

private object ClashKeyB : Event.Key<ClashB>

private object StandaloneKey : Event.Key<Standalone> {
    override val name = "registry.test.standalone"
}
