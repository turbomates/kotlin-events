package com.turbomates.event.catalog

import com.turbomates.event.Event
import com.turbomates.event.EventCatalog
import com.turbomates.event.EventRegistry
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/**
 * End to end over a real compilation: the events below are cataloged by the `event-ksp` processor
 * running on the test sources of this module, and found back through [EventRegistry.discovered].
 */
class EventCatalogTest {
    @Test
    fun `an event compiled with the processor is discovered`() {
        val events = EventRegistry.discovered()
        assertNotNull(events["catalog.test.declared"])
        assertEquals(
            CatalogedEvent.serializer().descriptor,
            events["catalog.test.declared"]?.descriptor
        )
    }

    @Test
    fun `a discovered event round trips through the discovered registry`() {
        val events = EventRegistry.discovered()
        val payload = events.encode(CatalogedEvent("test"))
        assertEquals(true, payload.contains("\"catalog.test.declared\""))
    }

    @Test
    fun `an event without a declared name is cataloged but never registered`() {
        // The catalog carries it so discovered() can rule out a route collision with it; the
        // registry never holds it — it is stored under its class name and needs no table.
        val cataloged = ServiceLoader.load(EventCatalog::class.java).flatMap { it.keys }
        assertTrue(cataloged.contains(CatalogedEvent))
        assertTrue(cataloged.contains(CatalogedDerivedEvent))
        assertNull(EventRegistry.discovered()["event.catalog.cataloged_derived_event"])
    }

    @Test
    fun `a declared name that answers the derived route of another event is refused`() {
        // DeclaredClash names itself with the very route DerivedRouted is already published under:
        // a queue bound to it would receive both. Neither event is in the real catalogs (both are
        // file private), the collision is what a compiled catalog of them would carry.
        val failure = assertFailsWith<IllegalArgumentException> {
            EventRegistry.of(listOf(catalog(DerivedRouted, DeclaredClash)))
        }
        assertTrue(failure.message!!.contains("event.catalog.derived_routed"))
    }

    @Test
    fun `two events sharing a derived route are refused`() {
        // Neither event declares a name, both keys derive the same route: the collision the broker
        // has always been silently living with is refused at startup now.
        assertEquals(FirstClashKey.name, SecondClashKey.name)
        val failure = assertFailsWith<IllegalArgumentException> {
            EventRegistry.of(listOf(catalog(FirstClashKey, SecondClashKey)))
        }
        assertTrue(failure.message!!.contains(FirstClashKey.name))
    }

    @Test
    fun `the same event met in two catalogs is not a collision`() {
        val events = EventRegistry.of(listOf(catalog(CatalogedEvent), catalog(CatalogedEvent)))
        assertNotNull(events["catalog.test.declared"])
    }

    private fun catalog(vararg keys: Event.Key<out Event>) = object : EventCatalog {
        override val keys: List<Event.Key<out Event>> = keys.toList()
    }
}

@Serializable
internal data class CatalogedEvent(val value: String) : Event() {
    override val key get() = Companion

    companion object : Key<CatalogedEvent> {
        override val name = "catalog.test.declared"
    }
}

@Serializable
internal data class CatalogedDerivedEvent(val value: String) : Event() {
    override val key get() = Companion

    companion object : Key<CatalogedDerivedEvent>
}

// The clash fixtures are file private on purpose: the processor skips them, so the real catalogs of
// this module stay collision free and only the hand-built ones of the tests above carry the clash.

@Serializable
private data class DerivedRouted(val value: String = "") : Event() {
    override val key get() = Companion

    companion object : Key<DerivedRouted>
}

@Serializable
private data class DeclaredClash(val value: String = "") : Event() {
    override val key get() = Companion

    companion object : Key<DeclaredClash> {
        override val name = "event.catalog.derived_routed"
    }
}

// Two standalone keys of one package derive one route: the name is built from the package alone.

@Serializable
private data class FirstClashEvent(val value: String = "") : Event() {
    override val key get() = FirstClashKey
}

@Serializable
private data class SecondClashEvent(val value: String = "") : Event() {
    override val key get() = SecondClashKey
}

private object FirstClashKey : Event.Key<FirstClashEvent>

private object SecondClashKey : Event.Key<SecondClashEvent>
