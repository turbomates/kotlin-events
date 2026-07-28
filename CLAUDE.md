# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

kotlin-events is a Kotlin library for event-driven architecture with support for transactional outbox pattern, RabbitMQ messaging, and distributed tracing. The library is published to Maven Central under the `com.turbomates` group.

## Essential Commands

### Build and Test
```bash
./gradlew build              # Build all modules
./gradlew test               # Run all tests
./gradlew :event:test        # Run tests for specific module
./gradlew clean build        # Clean build
```

### Code Quality
```bash
./gradlew detekt             # Run static code analysis
./gradlew detektMain         # Analyze main source code
./gradlew detektTest         # Analyze test code
```

### Publishing
```bash
# Publishing is handled by GitHub Actions
# Version is controlled by RELEASE_VERSION environment variable
```

### Running Single Test
```bash
./gradlew :event:test --tests "com.turbomates.event.LocalPublisherTest"
./gradlew :event-exposed:test --tests "com.turbomates.event.exposed.OutboxInterceptorTest"
```

## Module Architecture

The project is organized into four modules with clear separation of concerns:

### event (Core Module)
Foundation module providing core event-driven abstractions. All other modules depend on this.

**Key abstractions:**
- `Event` (abstract class): Base class for all events with `Event.Key<T>` for type-safe routing and an
  optional `partitionKey` (`@Transient`, override with a getter) that keeps a stream of events together
- `Publisher` (interface): Core interface for event publication using suspend functions
- `LocalPublisher`: In-process synchronous event publisher
- `SubscribersRegistry`: Type-safe registry mapping `Event.Key<T>` to subscribers
- `EventSubscriber<T>`: Handler for single event type with `invoke(event: T)` operator
- `EventStore`: Temporary FIFO storage for events raised during a transaction, drained in raise order
- `TelemetryService`: Interface for distributed tracing (default: NoOpTelemetryService)
- `TraceInformation`: W3C trace context carrier (traceparent, tracestate, baggage)

### event-exposed (Outbox Pattern Implementation)
Implements the transactional outbox pattern using Exposed ORM for PostgreSQL.

**Key components:**
- `Outbox`: Everything the outbox is made of, built by the application and shared by the interceptor and the publisher: bucket count and batch limit, serialization, bucket lock, retry policy, table instances, and the queries over them (`batchEventsInsert`, `nextSweep`, `tryLock`, `load`, `delete`, `failed`). All of it is called inside a transaction opened by the caller
- `OutboxRetryPolicy`: Exponential backoff of a failing event (`initialDelay * multiplier^(N-1)`, capped at `maxDelay`); no attempt limit and no dead-letter table on purpose — giving an event up would break the order of its stream, the unrecoverable row is deleted by hand
- `OutboxInterceptor`: Global Exposed interceptor that captures events during transactions via `EventStore` and hands them to `Outbox.batchEventsInsert`, registered with `Outbox.install()`
- `OutboxPublisher`: Background worker that sweeps the buckets of the `Outbox` and publishes events, it owns the transactions and the poll delay, not the outbox layout
- `PublicEvent`: Wrapper with UUIDv7 id, timestamp, bucket, and trace information for persistence
- `OutboxBucketLock`: Non blocking per-bucket lock, `PostgresAdvisoryBucketLock` uses `pg_try_advisory_xact_lock`
- `OutboxMetrics`: Per-bucket lag, batch size, owned buckets, for the application registry (default `NoOpOutboxMetrics`)
- `EventSourcingStorage`: Event sourcing support for aggregate reconstruction
- `EventSerialization`: `Json` and `KSerializer<Event>` of the jsonb columns, carried by the `Outbox`
- `EventsTable`: Database table for outbox events (jsonb event, bucket, partition_key, database-assigned sequence, attempts, next_attempt_at, trace_information, published_at)
- `EventSourcingTable`: Complete event history by rootId for event sourcing

**Pattern:** Events are persisted atomically with business data in the same transaction. A background coroutine sweeps buckets, locks one bucket at a time and delegates its events, one transaction each, to a chain of Publishers.

### event-rabbit (RabbitMQ Integration)
Provides RabbitMQ distribution with retry/dead-letter queue handling.

**Key components:**
- `RabbitPublisher`: Publishes events to RabbitMQ topic exchange with trace headers. Waits for the
  publisher confirm before returning (a nack or a timeout throws, so the outbox keeps the event),
  serializes publishes on one channel with a mutex (a confirm covers everything unconfirmed on the
  channel, not one message) and reopens connection and channel after a failure. `AutoCloseable`
- `RabbitQueue`: Consumer manager with Dead Letter Exchange (DLX) support
- `ListenerDeliveryCallback`: Handles message delivery with automatic retry logic
- `QueueConfig`: Configuration for queue behavior (prefetch, maxRetries, retryDelay)

**Retry mechanism:** 3-queue architecture per subscriber (Main Queue → DLX → Retry Queue → Main Queue → Parking Lot after max retries)

### event-telemetry-opentelemetry (Distributed Tracing)
OpenTelemetry implementation of `TelemetryService` for distributed tracing.

**Discovery:** Loaded via Java ServiceLoader. Falls back to NoOpTelemetryService if not available.

## Important Patterns

### Outbox Pattern Flow
1. Application raises event during transaction: `eventStore.addEvent(event)`
2. `OutboxInterceptor.beforeCommit()` persists events to `outbox_events` table atomically, each row
   carrying the bucket of `partitionKey ?: eventId`
3. `OutboxPublisher` sweeps the buckets given by `Outbox.nextSweep()` in a background coroutine, that
   list starts at a position rotating by one on every call
4. A transaction takes a non blocking advisory lock on the bucket and holds it for the whole batch,
   buckets held by another worker are skipped
5. Each event is published in a transaction of its own (`inTopLevelSuspendTransaction`, its own
   connection): the row is deleted first, then all publishers in chain are called (LocalPublisher,
   RabbitPublisher, etc.), then the transaction commits. Batches are ordered by the
   database-assigned `sequence` column, so events go out in the order they were raised
6. A publisher that throws rolls the deletion back; a separate transaction counts the attempt and
   sets `next_attempt_at` to `now() + backoff` (`OutboxRetryPolicy`). Until that moment the whole
   stream (`partition_key`) of the event is skipped — in the running batch and by `load` of later
   sweeps — so nothing is published out of order with its partition, other streams continue. A row
   that fails to decode is treated the same way (one failed attempt of its stream), not as a
   failure of the bucket

### Outbox Buckets
`Outbox(bucketCount = ...)` is required and has no default: it describes the data already written, not
a deployment, changing it re-maps every partition key. One `Outbox` instance is built at startup and
handed to both `install()` and `OutboxPublisher`, there is no process wide state behind it.
`batchLimit` is per bucket and belongs to the `Outbox` too, a sweep may publish
`batchLimit * bucketCount` events.

### Event Definition
```kotlin
class MyEvent(val data: String) : Event() {
    override val key: Key<out Event> = Companion
    companion object : Key<MyEvent>
}
```

### Subscriber Registration
```kotlin
// Single event subscriber
class MySubscriber : EventSubscriber<MyEvent>() {
    override suspend fun invoke(event: MyEvent) {
        // Handle event
    }
}

// Or use convenience function
MyEvent.subscriber { event ->
    // Handle event
}

// Register with registry
registry.registry(MySubscriber())
```

### Publisher Chain
```kotlin
val publishers = listOf(
    LocalPublisher(registry),  // In-process subscribers
    RabbitPublisher(config)    // Distributed messaging
)
val outboxPublisher = OutboxPublisher(database, publishers, Outbox(bucketCount = 16).also { it.install() })
```

## Testing

- Tests use JUnit Platform (kotlin.test)
- Coroutine tests use `runBlocking` wrapper
- Integration tests use Testcontainers (postgres, rabbitmq)
- Test files follow pattern: `*Test.kt` in `src/test/kotlin`

## Code Quality

- Static analysis: detekt with configuration in `detekt.yml`
- Formatting enforced via detekt-formatting plugin
- PR checks run via reviewdog GitHub Action
- Max issues set to 100000 in CI (via `yq -i '.build.maxIssues = 100000' detekt.yml`)

## Build Configuration

- Gradle version catalog in `settings.gradle.kts` under `deps` namespace
- Java 21 required
- Kotlin 2.2.20
- Multi-module setup with shared publishing configuration in root `build.gradle.kts`
- Maven Central publishing configured with Nexus Staging plugin

## Trace Context Propagation

W3C Trace Context flows end-to-end:
1. `TelemetryService.traceInformation()` captures current context
2. `OutboxInterceptor` stores in `outbox_events.trace_information`
3. `RabbitPublisher` embeds in message headers
4. `ListenerDeliveryCallback` extracts and creates child spans

## Key Dependencies

- kotlinx-serialization: Event serialization to JSON
- kotlinx-coroutines: Asynchronous event processing
- Exposed: Type-safe database ORM for outbox
- RabbitMQ AMQP Client: Message broker integration
- OpenTelemetry: Distributed tracing (optional, ServiceLoader)
- PostgreSQL: Database for outbox and event sourcing