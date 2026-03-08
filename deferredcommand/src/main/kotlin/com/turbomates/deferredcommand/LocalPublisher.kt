package com.turbomates.deferredcommand

class LocalPublisher(private val registry: SubscribersRegistry) : Publisher {
    override suspend fun publish(command: DeferredCommand) {
        registry.subscribers(command).forEach {
            it.invoke(command)
        }
    }
}
