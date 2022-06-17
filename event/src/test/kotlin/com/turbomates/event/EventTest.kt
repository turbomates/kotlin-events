package com.turbomates.event


class MyEvent1 : Event() {
    override val key get() = Companion

    companion object : Event.Key<MyEvent1>
}

class SyncNotificationsSubscriber() : EventsSubscriber {
    override fun subscribers() = listOf(
        MyEvent1.subscriber {
            println("MyEvent1")
        }
    )
}
