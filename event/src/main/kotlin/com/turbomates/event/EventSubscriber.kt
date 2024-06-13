package com.turbomates.event

import kotlin.reflect.KClass

abstract class EventSubscriber<T : Event>(val key: Event.Key<T>) {
    fun name(): String = this::class.scopedName()
    abstract suspend operator fun invoke(event: T)
}

fun KClass<out EventSubscriber<out Event>>.scopedName(): String {
    return "${moduleName()}.$simpleName"
}

inline fun <reified TEvent : Event, reified TKey : Event.Key<TEvent>> TKey.subscriber(crossinline action: suspend (TEvent) -> Unit): EventSubscriber<TEvent> =
    object : EventSubscriber<TEvent>(this) {
        override suspend fun invoke(event: TEvent) = action(event)
    }
