package com.turbomates.deferredcommand.exposed

import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.serializer.LocalDateTimeSerializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
    fun `schedule inserts command`() = runBlocking {
        val scheduler = ExposedScheduler(database)
        scheduler.schedule(SchedulerTestDeferredCommand(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "sync-orders"))

        transaction(database) {
            assertEquals(1, DeferredCommandsTable.selectAll().count())
        }
    }
}

@Serializable
private data class SchedulerTestDeferredCommand(
    @Serializable(with = LocalDateTimeSerializer::class)
    override val executeAt: LocalDateTime,
    val name: String
) : DeferredCommand() {
    override val key: Key<out DeferredCommand>
        get() = Companion

    companion object : Key<SchedulerTestDeferredCommand>
}
