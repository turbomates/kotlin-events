package com.turbomates.event

import kotlin.reflect.KClass

class SubscribersRegistry {
    private val eventSubscribersMap: MutableMap<Event.Key<out Event>, MutableList<EventSubscriber<out Event>>> =
        mutableMapOf()
    private val eventsSubscribers = mutableListOf<EventsSubscriber>()

    private val eventSubscribers = mutableListOf<EventSubscriber<out Event>>()

    fun registry(subscriber: EventsSubscriber) {
        subscriber.subscribers().forEach {
            addToMap(it.key, it)
        }
        eventsSubscribers.add(subscriber)
    }

    fun registry(subscriber: EventSubscriber<out Event>) {
        addToMap(subscriber.key, subscriber)
        eventSubscribers.add(subscriber)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Event> subscribers(event: T): List<EventSubscriber<T>> {
        return eventSubscribersMap.getOrDefault(event.key, listOf()).toList() as List<EventSubscriber<T>>
    }

    fun subscribers(): Subscribers {
        return Subscribers(eventsSubscribers, eventSubscribers)
    }

    private fun addToMap(key: Event.Key<out Event>, subscriber: EventSubscriber<out Event>) {
        val list = eventSubscribersMap.getOrPut(key) {
            mutableListOf()
        }
        list.add(subscriber)
    }
}

data class Subscribers(val eventsSubscribers: List<EventsSubscriber>, val eventSubscribers: List<EventSubscriber<out Event>>)

abstract class EventSubscriber<T : Event>(val key: Event.Key<T>) {
    abstract suspend operator fun invoke(event: T)
}


inline fun <reified TEvent : Event, reified TKey : Event.Key<TEvent>> TKey.subscriber(crossinline action: suspend (TEvent) -> Unit): EventSubscriber<TEvent> =
    object : EventSubscriber<TEvent>(this) {
        override suspend fun invoke(event: TEvent) = action(event)
    }

internal fun KClass<*>.moduleName() = qualifiedName!!.split(".")[2]
