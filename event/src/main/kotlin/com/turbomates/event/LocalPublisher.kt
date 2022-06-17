package com.turbomates.event


class LocalPublisher(private val registry: SubscribersRegistry) : Publisher {
    override suspend fun publish(event: Event) {
        registry.subscribers(event).forEach {
            it.invoke(event)
        }
    }

}
