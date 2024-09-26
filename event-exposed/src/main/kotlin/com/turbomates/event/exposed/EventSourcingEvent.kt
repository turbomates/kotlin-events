package com.turbomates.event.exposed

import com.turbomates.event.Event
import kotlinx.serialization.Serializable

@Serializable
abstract class EventSourcingEvent(val rootId: String) : Event()
