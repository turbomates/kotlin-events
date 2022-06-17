package com.turbomates.event.rabbit

import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import kotlin.reflect.KClass

internal fun Event.Key<*>.routeName(): String {
    val packagePath = this::class.qualifiedName!!.split('.').dropLast(1)
    return packagePath.takeLast(3).joinToString(".").camelToSnakeCase()
}

internal fun EventSubscriber<*>.queueName(prefix: String): String {
    val packagePath = this::class.qualifiedName!!
    return queueName(packagePath, prefix)
}

internal fun EventsSubscriber.queueName(prefix: String): String {
    val packagePath = this::class.qualifiedName!!
    return queueName(packagePath, prefix)
}

private fun queueName(packagePath: String, prefix: String): String {
    val splitPath = packagePath.split('.')
    return prefix + "." + splitPath.getOrNull(2)?.lowercase() + "." + splitPath.takeLast(1)
        .joinToString(separator = ".") { it.camelToSnakeCase() }
}

private fun String.camelToSnakeCase(): String {
    return camelRegex.replace(this) {
        "_${it.value}"
    }.lowercase()
}

private val camelRegex = "(?<=[a-zA-Z])[A-Z]".toRegex()

internal fun KClass<out EventsSubscriber>.queueName(prefix: String): String {
    return queueName(this.qualifiedName!!, prefix)
}

@JvmName("eventSubscriber")
internal fun KClass<out EventSubscriber<*>>.queueName(prefix: String): String {
    return queueName(this.qualifiedName!!, prefix)
}
