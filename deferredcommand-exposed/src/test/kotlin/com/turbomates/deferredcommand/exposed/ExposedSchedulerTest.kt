package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.serializer.LocalDateTimeSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

class ExposedSchedulerTest {
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
    fun `schedule inserts command with trace information`() = runBlocking {
        val scheduler = ExposedScheduler(database)
        scheduler.schedule(TestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "sync-orders"))

        transaction(database) {
            assertEquals(1, DeferredCommandsTable.selectAll().count())
            val row = DeferredCommandsTable.selectAll().first()
            assertNotNull(row[DeferredCommandsTable.traceInformation])
        }
    }
}

@Serializable
private data class TestDeferredCommand(
    @Serializable(with = LocalDateTimeSerializer::class)
    override val executeAt: LocalDateTime,
    val name: String
) : DeferredCommand() {
    override val key: Key<out DeferredCommand>
        get() = Companion

    companion object : Key<TestDeferredCommand>
}
