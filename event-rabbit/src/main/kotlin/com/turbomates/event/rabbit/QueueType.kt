package com.turbomates.event.rabbit

enum class QueueType(val value: String) {
    CLASSIC("classic"),
    QUORUM("quorum"),
}
