package com.turbomates.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class EventRegistryTest {
    @Test
    fun `a declared name resolves to the serializer of its event`() {
        val registry = EventRegistry(Declared)
        assertEquals(Declared.serializer().descriptor, registry["registry.test.declared"]?.descriptor)
    }

    @Test
    fun `an event with no declared name is held under the name derived from its class`() {
        val registry = EventRegistry(Derived)
        assertEquals(Derived.serializer().descriptor, registry["turbomates.event.derived"]?.descriptor)
    }

    @Test
    fun `registering the same event twice is allowed`() {
        val registry = EventRegistry(Declared)
        registry.register(Declared)
        assertNotNull(registry["registry.test.declared"])
    }

    @Test
    fun `the same event registered through two serializer instances is one registration`() {
        // A KSerializer has no equals, and the generated one is a singleton only while the event is
        // not generic: comparing instances would refuse the second catalog holding a generic event.
        val registry = EventRegistry()
        registry.register(Derived, Passthrough())
        registry.register(Derived, Passthrough())
        assertNotNull(registry["turbomates.event.derived"])
    }

    @Test
    fun `an event is written with the serializer registered for it`() {
        val registry = EventRegistry()
        registry.register(Declared, Shouted)
        assertEquals(true, registry.encode(Declared("quiet")).contains("QUIET"))
    }

    @Test
    fun `two events cannot answer one name`() {
        val registry = EventRegistry(Declared)
        val failure = assertFailsWith<IllegalArgumentException> {
            registry.register(key<Derived>("registry.test.declared"), Derived.serializer())
        }
        assertEquals(true, failure.message?.contains("registry.test.declared"))
    }

    @Test
    fun `a name of one segment is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventRegistry().register(key<Derived>("flat"), Derived.serializer())
        }
        assertEquals(true, failure.message?.contains("flat"))
    }

    @Test
    fun `a name that is not snake case is refused`() {
        assertFailsWith<IllegalArgumentException> {
            EventRegistry().register(key<Derived>("registry.test.NotSnakeCase"), Derived.serializer())
        }
    }

    @Test
    fun `a key that is not the companion of its event needs its serializer`() {
        val standalone = key<Derived>("registry.test.standalone")
        val failure = assertFailsWith<IllegalArgumentException> { EventRegistry().register(standalone) }
        assertEquals(true, failure.message?.contains("registry.test.standalone"))
        val registry = EventRegistry()
        registry.register(standalone, Derived.serializer())
        assertNotNull(registry["registry.test.standalone"])
    }

    @Test
    fun `a registered name is told apart from one nothing answers`() {
        val registry = EventRegistry(Declared)
        assertEquals(true, "registry.test.declared" in registry)
        assertEquals(false, "registry.test.unregistered" in registry)
    }

    /**
     * A key with no class of its own: the registry takes it like any other, and the processor never
     * sees it, which is what keeps the fixtures of the tests below out of the generated catalog.
     */
    private fun <T : Event> key(declared: String) = object : Event.Key<T> {
        override val name = declared
    }

    /** The generated serializer of [Derived] in a fresh instance on every construction. */
    private class Passthrough : KSerializer<Derived> by Derived.serializer()

    /** A serializer of one's own, told apart from the generated one by what it writes. */
    private object Shouted : KSerializer<Declared> {
        override val descriptor = Declared.serializer().descriptor
        override fun serialize(encoder: Encoder, value: Declared) =
            Declared.serializer().serialize(encoder, Declared(value.value.uppercase()))

        override fun deserialize(decoder: Decoder): Declared = Declared.serializer().deserialize(decoder)
    }
}

@Serializable
internal data class Declared(val value: String = "") : Event() {
    override val key get() = Companion

    companion object : Key<Declared> {
        override val name = "registry.test.declared"
    }
}

@Serializable
internal data class Derived(val value: String = "") : Event() {
    override val key get() = Companion

    companion object : Key<Derived>
}
