package com.turbomates.event.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import java.security.MessageDigest

class EventCatalogProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        EventCatalogProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Writes the event catalog of a module at compile time: an `EventCatalog` listing the key of every
 * concrete `Event` in the compilation, plus the `META-INF/services` entry that lets
 * `EventRegistry.discovered()` find it. The table from a stored name back to a serializer has to be
 * maintained by someone — this makes it the compiler, so declaring the name on the key is the whole
 * of what an event author does, and there is no registration to forget.
 *
 * Events without a declared name are carried too, on purpose: the registry never holds them — they
 * are stored under their class name and need no table — but their derived routes exist in the broker
 * all the same, and `discovered()` refuses two events answering one route, declared or derived
 * alike. Without the unmigrated keys that check would be blind to them.
 *
 * Only events whose key is their companion object are cataloged — that is the documented shape, and
 * it is what ties the key to the class the serializer comes from. A named key declared as a
 * standalone object, or a named event that is not visible outside its file, is reported and left to
 * be registered by hand; an unnamed event in either shape needs nothing and is skipped in silence.
 */
class EventCatalogProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    private val events = sortedSetOf<String>()
    private val files = mutableSetOf<KSFile>()
    private var isGenerated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val event = resolver.type(EVENT) ?: return emptyList()
        val key = resolver.type(KEY) ?: return emptyList()
        resolver.getNewFiles().forEach { file ->
            file.classes().forEach { declaration -> declaration.catalog(event, key, file) }
        }
        generate()
        return emptyList()
    }

    private fun KSClassDeclaration.catalog(event: KSType, key: KSType, file: KSFile) {
        if (Modifier.ABSTRACT in modifiers || Modifier.SEALED in modifiers) return
        if (classKind == ClassKind.OBJECT && !isCompanionObject &&
            key.isAssignableFrom(asStarProjectedType()) && declaresName()
        ) {
            val standalone = qualifiedName?.asString() ?: return
            logger.warn(
                "$standalone declares an event name but is not the companion object of its event, " +
                    "so the catalog cannot pair it with a serializer. Register it by hand: " +
                    "EventRegistry.register(key, serializer).",
                this
            )
            return
        }
        if (classKind != ClassKind.CLASS || !event.isAssignableFrom(asStarProjectedType())) return
        val name = qualifiedName?.asString() ?: return
        val companion = declarations.filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?.takeIf { key.isAssignableFrom(it.asStarProjectedType()) }
            ?: return
        if (getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
            // A named event the catalog cannot reference is one its author expects to be found;
            // an unnamed one expects nothing and is left alone.
            if (companion.declaresName()) {
                logger.warn(
                    "$name declares an event name but is not visible outside its file, so the " +
                        "generated event catalog cannot reference it. Make it internal, or register " +
                        "it by hand.",
                    this
                )
            }
            return
        }
        val keyProperty = getDeclaredProperties().firstOrNull { it.simpleName.asString() == "key" }
        if (keyProperty != null && keyProperty.hasBackingField) {
            logger.warn(
                "$name overrides `key` with a backing field, which puts the key into the serialized " +
                    "payload and fails the write when defaults are encoded — the Json of the outbox " +
                    "and the rabbit publisher does. Override it with a getter: " +
                    "`override val key get() = Companion`.",
                keyProperty
            )
        }
        events += name
        files += file
    }

    /**
     * Whether this key overrides [Event.Key.name]: the property resolves to a declaration of its own
     * rather than to the derived default of `Event.Key`. The value of the override is a runtime
     * matter, the fact of it is what separates a migrated event from one left alone.
     */
    private fun KSClassDeclaration.declaresName(): Boolean {
        val name = getAllProperties().firstOrNull { it.simpleName.asString() == "name" } ?: return false
        val declaredIn = name.parentDeclaration?.qualifiedName ?: return false
        return declaredIn.asString() != KEY
    }

    /**
     * One catalog per compilation, written at the end of the first round: the catalog itself
     * declares no events, so later rounds have nothing to add to it.
     */
    @Suppress("SpreadOperator") // Dependencies only takes a vararg, and this runs once per compilation
    private fun generate() {
        if (isGenerated || events.isEmpty()) return
        isGenerated = true
        val name = "EventCatalog" + suffix()
        val dependencies = Dependencies(aggregating = true, *files.toTypedArray())
        codeGenerator.createNewFile(dependencies, PACKAGE, name).bufferedWriter().use { catalog ->
            catalog.write("package $PACKAGE\n\n")
            catalog.write("import com.turbomates.event.Event\n")
            catalog.write("import com.turbomates.event.EventCatalog\n\n")
            catalog.write("public class $name : EventCatalog {\n")
            catalog.write("    override val keys: List<Event.Key<out Event>> = listOf(\n")
            events.forEach { event -> catalog.write("        $event,\n") }
            catalog.write("    )\n")
            catalog.write("}\n")
        }
        codeGenerator.createNewFileByPath(dependencies, SERVICE, extensionName = "").bufferedWriter().use { service ->
            service.write("$PACKAGE.$name\n")
        }
    }

    /**
     * The class name has to be unique per module, or two catalogs meeting on one classpath would
     * collide, and there is no module name to build it from — so it is built from the events
     * themselves. Two modules can only clash by declaring the very same set of classes.
     */
    private fun suffix(): String {
        val digest = MessageDigest.getInstance("MD5").digest(events.joinToString(",").toByteArray())
        return digest.take(HASH_BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Resolver.type(name: String): KSType? =
        getClassDeclarationByName(getKSNameFromString(name))?.asStarProjectedType()

    private fun KSFile.classes(): Sequence<KSClassDeclaration> =
        declarations.filterIsInstance<KSClassDeclaration>().flatMap { declaration ->
            sequenceOf(declaration) + declaration.declarations.filterIsInstance<KSClassDeclaration>()
        }

    private companion object {
        const val EVENT = "com.turbomates.event.Event"
        const val KEY = "com.turbomates.event.Event.Key"
        const val PACKAGE = "com.turbomates.event.catalog"
        const val SERVICE = "META-INF/services/com.turbomates.event.EventCatalog"
        const val HASH_BYTES = 4
    }
}
