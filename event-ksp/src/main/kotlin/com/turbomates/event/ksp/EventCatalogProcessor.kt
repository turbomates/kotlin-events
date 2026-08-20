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
 * event of it, plus the `META-INF/services` entry that lets `EventRegistry.discovered()` find it.
 * The table from a stored name back to a serializer has to be maintained by someone — this makes it
 * the compiler, and there is no registration to forget.
 *
 * The catalog is exhaustive, not a list of the events that opted in: a payload carries the
 * [Event.Key.name] of its event whether that name is declared or derived from the class, so an
 * event missing from every catalog is an event whose rows nothing can read. Declaring a name
 * changes what an event is called, never whether it is held.
 *
 * That is also why the shapes a catalog cannot carry are errors rather than warnings: an event that
 * is not visible outside its file, one whose key is not its companion object — the companion is what
 * ties the key to the class the serializer comes from — and one that is not `@Serializable`, which
 * has no serializer to be paired with at all. All three are reported where they are written: make it
 * internal, give it a companion key, annotate it, or register it by hand with
 * `EventRegistry.register(key, serializer)`. Failing the build beats leaving a hole that shows up as
 * an undecodable row months later, or as a startup that dies on an event nobody meant to store.
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
        if (!event.isAssignableFrom(asStarProjectedType())) return
        val name = qualifiedName?.asString() ?: return
        val companion = declarations.filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?.takeIf { key.isAssignableFrom(it.asStarProjectedType()) }
        if (classKind != ClassKind.CLASS || companion == null) {
            logger.error(
                "$name is an event whose key is not its companion object, so the catalog cannot " +
                    "pair its name with a serializer and nothing would be able to read its rows " +
                    "back. Give it `companion object : Key<${simpleName.asString()}>`, or register " +
                    "it by hand: " +
                    "EventRegistry.register(key, serializer).",
                this
            )
            return
        }
        if (getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
            logger.error(
                "$name is an event that is not visible outside its file, so the generated event " +
                    "catalog cannot reference it and nothing would be able to read its rows back. " +
                    "Make it internal, or register it by hand: EventRegistry.register(key, serializer).",
                this
            )
            return
        }
        if (!isSerializable()) {
            logger.error(
                "$name is an event that is not @Serializable, so the registry it is cataloged into " +
                    "could not produce a serializer for it and its rows could be neither written " +
                    "nor read. Annotate it with @Serializable.",
                this
            )
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
     * Whether the event carries `@Serializable`, with or without a serializer of its own: that is
     * what `KClass.serializer()` looks for when the registry pairs the name of this event with the
     * way to read it back.
     */
    private fun KSClassDeclaration.isSerializable(): Boolean = annotations.any { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == SERIALIZABLE
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
        const val SERIALIZABLE = "kotlinx.serialization.Serializable"
        const val PACKAGE = "com.turbomates.event.catalog"
        const val SERVICE = "META-INF/services/com.turbomates.event.EventCatalog"
        const val HASH_BYTES = 4
    }
}
