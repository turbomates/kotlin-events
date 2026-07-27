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
- `EventStore`: Temporary LIFO storage for events raised during a transaction
- `TelemetryService`: Interface for distributed tracing (default: NoOpTelemetryService)
- `TraceInformation`: W3C trace context carrier (traceparent, tracestate, baggage)

### event-exposed (Outbox Pattern Implementation)
Implements the transactional outbox pattern using Exposed ORM for PostgreSQL.

**Key components:**
- `OutboxInterceptor`: Global Exposed interceptor that captures events during transactions via `EventStore`
- `OutboxPublisher`: Background worker that sweeps the buckets of `outbox_events` and publishes events
- `PublicEvent`: Wrapper with UUID, timestamp, bucket, and trace information for persistence
- `OutboxBuckets`: Bucket count of the process (set once) and `partitionKey ?: eventId` bucket derivation
- `OutboxBucketLock`: Non blocking per-bucket lock, `PostgresAdvisoryBucketLock` uses `pg_try_advisory_xact_lock`
- `OutboxMetrics`: Per-bucket lag, batch size, owned buckets (`LoggingOutboxMetrics`, `InMemoryOutboxMetrics`)
- `EventSourcingStorage`: Event sourcing support for aggregate reconstruction
- `EventsTable`: Database table for outbox events (jsonb event, bucket, trace_information, published_at)
- `EventSourcingTable`: Complete event history by rootId for event sourcing

**Pattern:** Events are persisted atomically with business data in the same transaction. A background coroutine sweeps buckets, locks one bucket at a time and delegates its batch to a chain of Publishers.

### event-rabbit (RabbitMQ Integration)
Provides RabbitMQ distribution with retry/dead-letter queue handling.

**Key components:**
- `RabbitPublisher`: Publishes events to RabbitMQ topic exchange with trace headers
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
3. `OutboxPublisher` sweeps the buckets in a background coroutine, starting at a rotating position
4. Each bucket batch runs in its own transaction guarded by a non blocking advisory lock, buckets held
   by another worker are skipped
5. For each event, calls all publishers in chain (LocalPublisher, RabbitPublisher, etc.)
6. Removes the published row inside the same transaction

### Outbox Buckets
`OutboxPublisher(bucketCount = ...)` is required and has no default: it describes the data already
written, not a deployment, changing it re-maps every partition key. Building the publisher configures
`OutboxBuckets` for the whole process (a writer only process calls `OutboxBuckets.configure()` itself),
and a second, different count throws `OutboxBucketCountMismatchException`.
`batchSize` is per bucket, a sweep may publish `batchSize * bucketCount` events.

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
val outboxPublisher = OutboxPublisher(database, publishers, bucketCount = 16)
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