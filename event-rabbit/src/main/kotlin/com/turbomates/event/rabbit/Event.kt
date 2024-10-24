package com.turbomates.event.rabbit

import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber

internal fun Event.Key<*>.routeName(): String {
    val eventClassPath = this::class.qualifiedName!!.split('.').dropLast(1)
    return eventClassPath.takeLast(3).joinToString(".").camelToSnakeCase()
}

fun EventSubscriber<*>.queueName(prefix: String): String {
    return queueName(this.name(), prefix)
}

fun EventsSubscriber.queueName(prefix: String): String {
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


