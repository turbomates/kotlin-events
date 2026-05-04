package com.turbomates.deferredcommand.rabbit

enum class QueueType(val value: String) {
    CLASSIC("classic"),
    QUORUM("quorum"),
}
