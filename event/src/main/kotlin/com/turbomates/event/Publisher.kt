package com.turbomates.event

interface Publisher {
    suspend fun publish(event: Event, traceInformation: TraceInformation? = null)
}
