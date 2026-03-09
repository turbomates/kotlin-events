package com.turbomates.deferredcommand

import kotlin.reflect.KClass

class SubscribersRegistry {
    private val commandSubscribersMap: MutableMap<DeferredCommand.Key<out DeferredCommand>, MutableList<DeferredCommandSubscriber<out DeferredCommand>>> =
        mutableMapOf()
    private val deferredCommandsSubscribers = mutableListOf<DeferredCommandsSubscriber>()
    private val deferredCommandSubscribers = mutableListOf<DeferredCommandSubscriber<out DeferredCommand>>()

    fun register(subscriber: DeferredCommandsSubscriber) {
        subscriber.subscribers().forEach {
            addToMap(it.key, it)
        }
        deferredCommandsSubscribers.add(subscriber)
    }

    fun register(subscriber: DeferredCommandSubscriber<out DeferredCommand>) {
        addToMap(subscriber.key, subscriber)
        deferredCommandSubscribers.add(subscriber)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : DeferredCommand> subscribers(command: T): List<DeferredCommandSubscriber<T>> {
        return commandSubscribersMap.getOrDefault(command.key, listOf()).toList() as List<DeferredCommandSubscriber<T>>
    }

    fun subscribers(): Subscribers {
        return Subscribers(deferredCommandsSubscribers, deferredCommandSubscribers)
    }

    private fun addToMap(key: DeferredCommand.Key<out DeferredCommand>, subscriber: DeferredCommandSubscriber<out DeferredCommand>) {
        val list = commandSubscribersMap.getOrPut(key) {
            mutableListOf()
        }
        list.add(subscriber)
    }
}

data class Subscribers(
    val deferredCommandsSubscribers: List<DeferredCommandsSubscriber>,
    val deferredCommandSubscribers: List<DeferredCommandSubscriber<out DeferredCommand>>
)

internal fun KClass<*>.moduleName() = qualifiedName!!.split(".")[2]
