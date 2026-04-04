package com.turbomates.deferredcommand

import kotlin.reflect.KClass

interface DeferredCommandsSubscriber {
    fun name(): String = this::class.scopedName()
    fun subscribers(): List<DeferredCommandSubscriber<out DeferredCommand>>

    infix fun <TCommand : DeferredCommand, TKey : DeferredCommand.Key<TCommand>, TSubscriber : DeferredCommandSubscriber<TCommand>> TKey.to(
        that: TSubscriber
    ): DeferredCommandSubscriber<TCommand> = that
}

fun KClass<out DeferredCommandsSubscriber>.scopedName(): String {
    return "${moduleName()}.${requireNotNull(simpleName)}"
}
