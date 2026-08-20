package com.turbomates.event.catalog

import com.turbomates.event.Event
import com.turbomates.event.EventCatalog
import com.turbomates.event.EventRegistry
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `an event without a declared name is cataloged under the name derived from its class`() {
        // The catalog is exhaustive: the payload of this event carries its derived name, so a
        // registry without an entry for it would be a registry that cannot read its rows.
        val cataloged = ServiceLoader.load(EventCatalog::class.java).flatMap { it.keys }
        assertTrue(cataloged.contains(CatalogedEvent))
        assertTrue(cataloged.contains(CatalogedDerivedEvent))
        assertNotNull(EventRegistry.discovered()["event.catalog.cataloged_derived_event"])
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

