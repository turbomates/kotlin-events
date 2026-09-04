package com.turbomates.event

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * UUIDv7 of the standard library: millisecond timestamp in the high bits, monotonic within the
 * process even inside one millisecond. It is the [Event.eventId], and with it the primary key of
 * every outbox and event sourcing row, so those indexes append instead of splitting pages all over
 * the tree. Identity only: publish order comes from the `sequence` column of the outbox, the
 * timestamp inside the id is never read back.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun uuidV7(): UUID = Uuid.generateV7().toJavaUuid()
