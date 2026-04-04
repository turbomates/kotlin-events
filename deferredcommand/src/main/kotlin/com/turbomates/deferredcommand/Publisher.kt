package com.turbomates.deferredcommand

import com.turbomates.event.TraceInformation

interface Publisher {
    suspend fun publish(command: DeferredCommand, traceInformation: TraceInformation? = null)
}
