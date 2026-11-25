package com.turbomates.event.exposed

import com.turbomates.event.Event
import java.lang.reflect.Proxy
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.statements.api.ExposedSavepoint
import org.jetbrains.exposed.v1.jdbc.statements.api.ExposedConnection

class OutboxConnectionTest {
    private var duringRollback: () -> Unit = {}
    private val connection = OutboxConnection(databaseConnection())

    @Test
    fun `an event that comes while the connection rolls back to a savepoint is kept`() {
        // Its data is written after the rollback, the connection runs one statement at a time.
        val event = PublicEvent(TestEvent())
        val savepoint = connection.setSavepoint("savepoint")
        connection.defer(listOf(PublicEvent(TestEvent())))
        lateinit var neighbour: Thread
        duringRollback = {
            neighbour = thread { connection.defer(listOf(event)) }
            neighbour.join(LONG_ENOUGH_TO_GET_IN)
        }

        connection.rollback(savepoint)

        neighbour.join()
        assertEquals(listOf(event), connection.drain())
    }

    @Test
    fun `a rollback of the whole transaction forgets the events and the savepoints`() {
        connection.setSavepoint("savepoint")
        connection.defer(listOf(PublicEvent(TestEvent())))

        connection.rollback()

        val event = PublicEvent(TestEvent())
        assertEquals(listOf(event), connection.defer(listOf(event)))
    }

    @Test
    fun `a savepoint the connection failed to roll back to leaves the events`() {
        val event = PublicEvent(TestEvent())
        val savepoint = connection.setSavepoint("savepoint")
        connection.defer(listOf(event))
        duringRollback = { error("the savepoint does not exist") }

        runCatching { connection.rollback(savepoint) }

        assertEquals(listOf(event), connection.drain())
    }

    @Suppress("UNCHECKED_CAST")
    private fun databaseConnection() = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(ExposedConnection::class.java)
    ) { _, method, arguments ->
        when (method.name) {
            "setSavepoint" -> object : ExposedSavepoint(arguments.single() as String) {}
            "rollback" -> if (arguments == null) Unit else duringRollback()
            else -> error("${method.name} is not expected")
        }
    } as ExposedConnection<Any>

    @Serializable
    private class TestEvent : Event() {
        override val key get() = TestEvent

        companion object : Key<TestEvent>
    }

    private companion object {
        const val LONG_ENOUGH_TO_GET_IN = 200L
    }
}
