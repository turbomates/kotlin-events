package com.turbomates.event

/**
 * The name [Event.Key.name] falls back to when the event does not declare one: the last three
 * segments of the package of the key plus the event itself, snake_cased —
 * `billing.subscription.subscription_created`.
 *
 * This is exactly what the routing keys of this library were derived from before names existed, so
 * an application that declares nothing keeps its bindings untouched.
 *
 * It is not an identity, it is a shadow of the code: moving the event to another package or renaming
 * it produces a different name, and both the queues bound to the old one and the rows written under
 * it are left behind. Declare [Event.Key.name] instead.
 */
@Deprecated(
    "A name derived from the class of the key changes when the event is renamed or moved, which " +
        "orphans queue bindings and makes stored rows unreadable. Declare a stable name instead: " +
        "override val name = \"billing.subscription.created\"."
)
@Suppress("DEPRECATION")
fun Event.Key<*>.derivedName(): String = derivedNameOrNull()
    ?: error("An anonymous event key has no class to derive a name from, override Event.Key.name")

/**
 * [derivedName] of a key that has a class to derive it from, null for an anonymous one — which is
 * how a name that could only have been declared is told apart, see [hasDeclaredName].
 */
@Deprecated("The nullable form of derivedName(), and just as fragile. Declare Event.Key.name instead.")
fun Event.Key<*>.derivedNameOrNull(): String? {
    val path = this::class.qualifiedName ?: return null
    return path.split('.').dropLast(1).takeLast(NAME_SEGMENTS).joinToString(".").camelToSnakeCase()
}

/**
 * Whether the name of this key was declared rather than derived from its class.
 *
 * Nothing about how the event is stored or registered turns on this — the name is the name either
 * way. It separates the events that have been given an identity from the ones still carrying the
 * shape of the code: what is left of it is the routing of a rolling deploy, where the route of an
 * event changed the moment its name was declared, see `Config.bindLegacyRoutes`.
 */
@Suppress("DEPRECATION")
fun Event.Key<*>.hasDeclaredName(): Boolean = name != derivedNameOrNull()

private const val NAME_SEGMENTS = 3

// The rabbit module snake_cases queue names with a copy of this: both are four lines and neither is
// worth a public function on the core module.
private val camelRegex = "(?<=[a-zA-Z])[A-Z]".toRegex()

private fun String.camelToSnakeCase(): String = camelRegex.replace(this) { "_${it.value}" }.lowercase()
