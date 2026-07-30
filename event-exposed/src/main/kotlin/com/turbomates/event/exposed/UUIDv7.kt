package com.turbomates.event.exposed

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * UUIDv7 of the standard library: millisecond timestamp in the high bits, monotonic within the
 * process even inside one millisecond. Outbox and event sourcing rows get ever growing keys, so
 * the primary key index appends instead of splitting pages all over the tree. Identity only:
 * publish order comes from the `sequence` column, the timestamp inside the id is never read back.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun uuidV7(): UUID = Uuid.generateV7().toJavaUuid()
