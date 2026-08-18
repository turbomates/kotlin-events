package com.turbomates.event.rabbit

import com.rabbitmq.client.ShutdownSignalException
import com.rabbitmq.client.impl.AMQImpl
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenerShutdownCallbackTest {
    @Test
    fun `a channel that went down on its own is rebuilt`() {
        var lost = false
        val underTest = ListenerShutdownCallback("queue", recoversConnections = true) { lost = true }

        underTest.handleShutdownSignal("consumerTag", channelError())

        assertTrue(lost)
    }

    @Test
    fun `a channel closed by the application is the normal end of a consumer`() {
        var lost = false
        val underTest = ListenerShutdownCallback("queue", recoversConnections = true) { lost = true }

        underTest.handleShutdownSignal("consumerTag", channelError(initiatedByApplication = true))

        assertFalse(lost)
    }

    @Test
    fun `a connection is left to the automatic recovery of the client`() {
        var lost = false
        val underTest = ListenerShutdownCallback("queue", recoversConnections = true) { lost = true }

        underTest.handleShutdownSignal("consumerTag", connectionError())

        assertFalse(lost)
    }

    @Test
    fun `a connection is not rebuilt here even without the automatic recovery`() {
        var lost = false
        val underTest = ListenerShutdownCallback("queue", recoversConnections = false) { lost = true }

        underTest.handleShutdownSignal("consumerTag", connectionError())

        // Reopening the connection is out of reach of a consumer: they are opened once, by the
        // constructor of RabbitQueue. Retrying a channel on a dead one would spin forever.
        assertFalse(lost)
    }

    @Test
    fun `a handler that throws does not reach the client`() {
        val underTest = ListenerShutdownCallback("queue", recoversConnections = true) {
            throw IllegalStateException("broken")
        }

        underTest.handleShutdownSignal("consumerTag", channelError())
    }

    private fun channelError(initiatedByApplication: Boolean = false) = ShutdownSignalException(
        false,
        initiatedByApplication,
        AMQImpl.Channel.Close(PRECONDITION_FAILED, "PRECONDITION_FAILED - inequivalent arg", 0, 0),
        null
    )

    private fun connectionError() = ShutdownSignalException(
        true,
        false,
        AMQImpl.Connection.Close(CONNECTION_FORCED, "CONNECTION_FORCED - broker forced", 0, 0),
        null
    )

    private companion object {
        const val PRECONDITION_FAILED = 406
        const val CONNECTION_FORCED = 320
    }
}
