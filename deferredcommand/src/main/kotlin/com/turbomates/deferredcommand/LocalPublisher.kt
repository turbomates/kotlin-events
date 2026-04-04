package com.turbomates.deferredcommand

import com.turbomates.event.TraceInformation

class LocalPublisher(private val registry: SubscribersRegistry) : Publisher {
    override suspend fun publish(command: DeferredCommand, traceInformation: TraceInformation?) {
        registry.subscribers(command).forEach {
            it.invoke(command)
        }
    }
}
