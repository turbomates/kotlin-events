package com.turbomates.event.exposed

import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext

/**
 * Keeps the name of the open span in a thread local carried by the coroutine context, the way the
 * OpenTelemetry context is carried, so [StatementRecorder] sees which span a statement ran in.
 */
class ScopedTelemetry : Telemetry {
    val opened: MutableList<Opened> = CopyOnWriteArrayList()

    override fun traceInformation(): TraceInformation = TraceInformation(null, null, null)

    override suspend fun link(
        traceInformation: TraceInformation,
        spanName: String,
        attributes: Map<String, String>,
        block: suspend TraceInformation.() -> Unit
    ) {
        opened.add(Opened(spanName, attributes, traceInformation))
        withContext(current.asContextElement(spanName)) { block(traceInformation) }
    }

    override suspend fun <T> span(spanName: String, attributes: Map<String, String>, block: suspend () -> T): T {
        opened.add(Opened(spanName, attributes, null))
        return withContext(current.asContextElement(spanName)) { block() }
    }

    data class Opened(val name: String, val attributes: Map<String, String>, val origin: TraceInformation?)

    companion object {
        val current: ThreadLocal<String?> = ThreadLocal()
    }
}

/** Every statement and commit with the thread it ran on and the span of [ScopedTelemetry] open there. */
class StatementRecorder : GlobalStatementInterceptor {
    val entries: MutableList<Entry> = CopyOnWriteArrayList()

    override fun beforeExecution(transaction: Transaction, context: StatementContext) {
        record(context.sql(transaction))
    }

    override fun beforeCommit(transaction: Transaction) {
        record(COMMIT)
    }

    private fun record(what: String) {
        entries.add(Entry(Thread.currentThread().name, ScopedTelemetry.current.get(), what))
    }

    data class Entry(val thread: String, val span: String?, val what: String)

    companion object {
        const val COMMIT = "COMMIT"
    }
}
