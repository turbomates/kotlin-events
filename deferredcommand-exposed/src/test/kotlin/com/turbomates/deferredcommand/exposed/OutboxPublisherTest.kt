package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.Publisher
import com.turbomates.deferredcommand.serializer.LocalDateTimeSerializer
import com.turbomates.event.TraceInformation
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class OutboxPublisherTest {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        val underTest = PostgreSQLContainer("postgres:14").withEnv(mapOf("POSTGRES_USER" to "test", "POSTGRES_PASSWORD" to "test"))
        underTest.start()
        database = Database.connect(
            underTest.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = "test",
            password = "test"
        )
        transaction(database) {
            DeferredCommand::class.java.classLoader.getResourceAsStream("deferred_commands_postgres_table.sql")?.apply {
                exec(String(readAllBytes()))
            }
        }
    }

    @Test
    fun `worker publishes only due commands`() = runBlocking {
        val scheduler = ExposedScheduler(database)
        val publisher = CapturingPublisher()

        scheduler.schedule(WorkerTestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "due"))
        scheduler.schedule(WorkerTestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60), "future"))

        val outboxPublisher = OutboxPublisher(
            database = database,
            publishers = listOf(publisher),
            delay = 100.milliseconds
        )

        val job = outboxPublisher.start()
        withTimeout(5.seconds) {
            while (publisher.commands.isEmpty()) {
                delay(100.milliseconds)
            }
        }
        job.cancelAndJoin()

        assertEquals(1, publisher.commands.count())
        assertEquals("due", publisher.commands.first().name)
        assertNotNullTrace(publisher.traces.first())

        transaction(database) {
            assertEquals(1, DeferredCommandsTable.selectAll().count())
        }
    }

    @Test
    fun `every statement and commit of the worker runs inside a span of its telemetry`() = runBlocking {
        val scheduler = ExposedScheduler(database)
        val publisher = CapturingPublisher()
        val telemetry = ScopedTelemetry()
        val recorder = StatementRecorder()
        scheduler.schedule(WorkerTestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "due"))

        JdbcTransaction.globalInterceptors.add(recorder)
        val job = OutboxPublisher(
            database = database,
            publishers = listOf(publisher),
            delay = 100.milliseconds,
            telemetry = telemetry
        ).start()
        try {
            withTimeout(5.seconds) {
                while (publisher.commands.isEmpty() || telemetry.opened.count { it.name == OutboxPublisher.POLL_SPAN } < 2) {
                    delay(100.milliseconds)
                }
            }
        } finally {
            job.cancelAndJoin()
            JdbcTransaction.globalInterceptors.remove(recorder)
        }

        val ofWorker = recorder.entries.toList().filter { it.thread.startsWith("DefaultDispatcher") }
        assertTrue(ofWorker.isNotEmpty())
        assertEquals(emptyList(), ofWorker.filter { it.span == null }, "statements outside of a span")
        val commits = ofWorker.filter { it.what == StatementRecorder.COMMIT }.map { it.span }.toSet()
        assertEquals(setOf(OutboxPublisher.POLL_SPAN, OutboxPublisher.PUBLISH_SPAN), commits)
        assertTrue(ofWorker.any { it.span == OutboxPublisher.PUBLISH_SPAN && it.what.startsWith("DELETE") })
        val publish = telemetry.opened.single { it.name == OutboxPublisher.PUBLISH_SPAN }
        assertEquals(publisher.traces.single(), publish.origin)
        assertTrue(publish.attributes.containsKey(OutboxPublisher.COMMAND_ID_ATTRIBUTE))
    }

    @Test
    fun `worker keeps command when publish fails`() = runBlocking {
        val scheduler = ExposedScheduler(database)
        val publisher = AlwaysFailPublisher()

        scheduler.schedule(WorkerTestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "always-fail"))

        val outboxPublisher = OutboxPublisher(
            database = database,
            publishers = listOf(publisher),
            delay = 100.milliseconds
        )

        val job = outboxPublisher.start()
        withTimeout(5.seconds) {
            while (publisher.calls < 2) {
                delay(100.milliseconds)
            }
        }
        job.cancelAndJoin()

        assertTrue(publisher.calls >= 2)
        transaction(database) {
            assertEquals(1, DeferredCommandsTable.selectAll().count())
        }
    }

    @Test
    fun `a publisher that times out fails its command and the worker keeps polling`() = runBlocking {
        val scheduler = ExposedScheduler(database)
        val publisher = TimingOutPublisher()
        val reported = CopyOnWriteArrayList<Throwable>()
        scheduler.schedule(WorkerTestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "slow"))

        val job = OutboxPublisher(
            database = database,
            publishers = listOf(publisher),
            delay = 100.milliseconds,
            telemetry = ScopedTelemetry(),
            errorHandler = { _, error -> reported.add(error) }
        ).start()
        try {
            withTimeout(5.seconds) {
                while (publisher.calls.get() < 2) {
                    delay(100.milliseconds)
                }
            }
        } finally {
            job.cancelAndJoin()
        }

        assertTrue(reported.first() is TimeoutCancellationException)
    }

    @Test
    fun `a command interrupted by the stop of the worker is not reported as failed`() = runBlocking {
        val scheduler = ExposedScheduler(database)
        val started = CompletableDeferred<Unit>()
        val reported = CopyOnWriteArrayList<Throwable>()
        scheduler.schedule(WorkerTestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "interrupted"))

        val job = OutboxPublisher(
            database = database,
            publishers = listOf(SuspendedPublisher(started)),
            delay = 100.milliseconds,
            telemetry = ScopedTelemetry(),
            errorHandler = { _, error -> reported.add(error) }
        ).start()
        withTimeout(5.seconds) { started.await() }
        job.cancelAndJoin()

        assertEquals(emptyList(), reported.toList())
        transaction(database) {
            assertEquals(1, DeferredCommandsTable.selectAll().count())
        }
    }

    private fun assertNotNullTrace(traceInformation: TraceInformation?) {
        assertTrue(traceInformation != null)
    }

    private class CapturingPublisher : Publisher {
        val commands = mutableListOf<WorkerTestDeferredCommand>()
        val traces = mutableListOf<TraceInformation?>()

        override suspend fun publish(command: DeferredCommand, traceInformation: TraceInformation?) {
            commands.add(command as WorkerTestDeferredCommand)
            traces.add(traceInformation)
        }
    }

    private class TimingOutPublisher : Publisher {
        val calls = AtomicInteger()

        override suspend fun publish(command: DeferredCommand, traceInformation: TraceInformation?) {
            calls.incrementAndGet()
            withTimeout(10.milliseconds) { awaitCancellation() }
        }
    }

    private class SuspendedPublisher(private val started: CompletableDeferred<Unit>) : Publisher {
        override suspend fun publish(command: DeferredCommand, traceInformation: TraceInformation?) {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private class AlwaysFailPublisher : Publisher {
        var calls: Int = 0

        override suspend fun publish(command: DeferredCommand, traceInformation: TraceInformation?) {
            calls++
            throw IllegalStateException("broken")
        }
    }
}

@Serializable
private data class WorkerTestDeferredCommand(
    @Serializable(with = LocalDateTimeSerializer::class)
    override val executeAt: LocalDateTime,
    val name: String
) : DeferredCommand() {
    override val key: Key<out DeferredCommand>
        get() = Companion

    companion object : Key<WorkerTestDeferredCommand>
}
