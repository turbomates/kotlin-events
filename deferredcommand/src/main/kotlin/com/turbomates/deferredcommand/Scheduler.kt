package com.turbomates.deferredcommand

interface Scheduler {
    suspend fun schedule(command: DeferredCommand)
}
