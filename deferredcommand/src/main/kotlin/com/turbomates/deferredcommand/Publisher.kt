package com.turbomates.deferredcommand

interface Publisher {
    suspend fun publish(command: DeferredCommand)
}
