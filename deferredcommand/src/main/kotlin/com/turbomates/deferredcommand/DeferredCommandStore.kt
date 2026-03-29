package com.turbomates.deferredcommand

import java.util.LinkedList

class DeferredCommandStore {
    private val commands: LinkedList<DeferredCommand> = LinkedList()

    fun addCommand(command: DeferredCommand) {
        commands.push(command)
    }

    fun raiseCommands(): Sequence<DeferredCommand> = sequence {
        while (commands.isNotEmpty()) {
            yield(commands.pop())
        }
    }
}
