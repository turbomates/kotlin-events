package com.turbomates.event

/**
 * The declared-name events of one module, written at compile time by the `event-ksp` processor and
 * found through
 * [java.util.ServiceLoader]. [EventRegistry.discovered] folds every catalog on the classpath into
 * one registry, so an application whose modules compile with the processor registers nothing by
 * hand — declaring the name on the key is the whole of it.
 *
 * Implemented by generated code; there is no reason to implement it yourself, a hand-kept list
 * belongs in [EventRegistry.register] where forgetting it is at least local to one place.
 */
interface EventCatalog {
    val keys: List<Event.Key<out Event>>
}
