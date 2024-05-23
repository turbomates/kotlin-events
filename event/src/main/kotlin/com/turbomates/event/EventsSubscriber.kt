package com.turbomates.event

import kotlin.reflect.KClass

interface EventsSubscriber {
    fun subscribers(): List<EventSubscriber<out Event>>
    infix fun <TEvent : Event, TKey : Event.Key<TEvent>, TSubscriber : EventSubscriber<TEvent>> TKey.to(
        that: TSubscriber
    ): EventSubscriber<TEvent> =
        that
}

fun KClass<out EventsSubscriber>.scopedName(): String {
    return "${moduleName()}.$simpleName"
}
