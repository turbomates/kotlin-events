package com.turbomates.deferredcommand.rabbit

import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.DeferredCommandSubscriber
import com.turbomates.deferredcommand.DeferredCommandsSubscriber

internal fun DeferredCommand.Key<*>.routeName(): String {
    val commandClassPath = this::class.qualifiedName!!.split('.').dropLast(1)
    return commandClassPath.takeLast(3).joinToString(".").camelToSnakeCase()
}

fun DeferredCommandSubscriber<*>.queueName(prefix: String): String {
    return queueName(this.name(), prefix)
}

fun DeferredCommandsSubscriber.queueName(prefix: String): String {
    return queueName(this.name(), prefix)
}

private fun queueName(packagePath: String, prefix: String): String {
    return prefix + "." + packagePath.camelToSnakeCase()
}

private fun String.camelToSnakeCase(): String {
    return camelRegex.replace(this) {
        "_${it.value}"
    }.lowercase()
}

private val camelRegex = "(?<=[a-zA-Z])[A-Z]".toRegex()
