package com.turbomates.event.rabbit

import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.derivedNameOrNull

/** The routing key an event is published under: its [Event.Key.name], declared or derived. */
internal fun Event.Key<*>.routeName(): String = name

/**
 * The routing key this event was published under before it declared a name, the one queues are still
 * bound to on a broker that has been running since; null for a key with no class to derive it from.
 * Nothing publishes it any more, a queue is only bound to it while [Config.bindLegacyRoutes] holds,
 * see [RabbitQueue].
 */
@Suppress("DEPRECATION")
internal fun Event.Key<*>.legacyRouteName(): String? = derivedNameOrNull()

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


