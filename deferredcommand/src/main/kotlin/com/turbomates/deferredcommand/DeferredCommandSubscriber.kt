package com.turbomates.deferredcommand

import kotlin.reflect.KClass

abstract class DeferredCommandSubscriber<T : DeferredCommand>(val key: DeferredCommand.Key<T>) {
    fun name(): String = this::class.scopedName()
    abstract suspend operator fun invoke(command: T)
}

fun KClass<out DeferredCommandSubscriber<out DeferredCommand>>.scopedName(): String {
    return "${moduleName()}.${requireNotNull(simpleName)}"
}

inline fun <reified TCommand : DeferredCommand, reified TKey : DeferredCommand.Key<TCommand>> TKey.subscriber(
    crossinline action: suspend (TCommand) -> Unit
): DeferredCommandSubscriber<TCommand> =
    object : DeferredCommandSubscriber<TCommand>(this) {
        override suspend fun invoke(command: TCommand) = action(command)
    }
