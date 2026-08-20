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

The project is organized into five modules with clear separation of concerns:

### event (Core Module)
Foundation module providing core event-driven abstractions. All other modules depend on this.

**Key abstractions:**
- `Event` (abstract class): Base class for all events with `Event.Key<T>` for type-safe routing and an
  optional `partitionKey` (`@Transient`, override with a getter) that keeps a stream of events together
- `Event.Key.name`: The identity of an event — its routing key and the `type` of its stored payload.
  Declared by hand (`override val name = "billing.subscription.created"`), dot separated and
  `snake_case`, so a rename or a move of the class orphans nothing. Defaults to `derivedName()`, the
  name built from the package of the key the way routes always were, which keeps an application that
  declares nothing exactly where it was — deprecated, and as fragile as the class it follows, but not
  a second mechanism: declared or derived, the name is what is routed, stored and registered
- `EventRegistry`: the events of an application and how they are written — `name → KSerializer<Event>`
  plus the `Json` of every payload. Built once at startup and handed to the `Outbox`, the
  `EventSourcingStorage`, the `RabbitPublisher` and the `RabbitQueue`; it travels as one object because
  a registry and a format given out separately can drift apart. A name is all a reader has and there is
  no way from one to a class: `register(key)` takes the serializer off the class the key is the
  companion of, which is why every event has to be `@Serializable` — the processor fails the build on
  one that is not, instead of leaving it to the startup that registers it. Every event belongs here
  whether its name is
  declared or derived, because the payload carries the name either way; two events answering one name
  are refused, which is also how a collision between two derived names is finally diagnosed. The form
  (`snake_case`, two segments) is asked of a declared name only — a derived one has been the routing
  key of that event all along, and refusing it would fail the startup of an application that changed
  nothing. Consumers register themselves, see `RabbitQueue`
- `EventSerializer`: `{"type": .., "body": {..}}` of a stored event, a class over an `EventRegistry`
  (`EventRegistry.serializer`). The `type` is the name of the event, resolved back through the
  registry, and the `body` is written with the same entry it is read with, so an event registered
  with a serializer of its own is stored in the shape that serializer gives it; an event the registry
  does not hold is written under the serializer of its own class. A `type` the registry does not
  hold is read by loading it as a class — how every payload
  of this library used to be written, and the only way to read a row stored before the name reached
  the payload. The serializer itself never refuses a write, and neither does the outbox: the business
  data of a transaction does not depend on the delivery of its events, so a name nothing registered
  costs a row the sweep cannot decode — deferred and reported through `OutboxMetrics.eventFailed` and
  the error handler — rather than the work that raised it. Registration is a fact about the build, and
  the `event-ksp` processor is what reports it
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
- `OutboxInterceptor`: Global Exposed interceptor that captures events during transactions via `EventStore` and hands them to `Outbox.batchEventsInsert`, registered with `Outbox.install()`. Whatever is raised is written: an event whose name the registry does not hold makes a row the sweep cannot decode, which it defers and reports, while the transaction that raised it commits — the outbox exists so that business data does not depend on the delivery of its events
- `OutboxPublisher`: Background worker that sweeps the buckets of the `Outbox` and publishes events, it owns the transactions and the poll delay, not the outbox layout
- `PublicEvent`: Wrapper with UUIDv7 id, timestamp, bucket, and trace information for persistence
- `OutboxBucketLock`: Non blocking per-bucket lock, `PostgresAdvisoryBucketLock` uses `pg_try_advisory_xact_lock`
- `OutboxMetrics`: Per-bucket lag, batch size, owned buckets, per-event failures, total outbox depth on its own ticker, for the application registry (default `NoOpOutboxMetrics`)
- `FailedEvent`: What the `errorHandler` of `OutboxPublisher` is called with, together with the
  failure — the event itself (null for a row that can not be decoded, `payload` holds it as stored),
  bucket, partition key and attempts made. Observation only, the publisher logs and defers the row on
  its own; a handler that throws is logged and ignored. Failures that are not about one event stay
  with the log and `OutboxMetrics`
- `EventSourcingStorage`: Event sourcing support for aggregate reconstruction. Takes the same
  `EventRegistry` instance the `Outbox` holds — it is the one that wrote the rows, a registry of its
  own would silently miss the history written under every name it does not hold
- `EventsTable`: Database table for outbox events (jsonb event, bucket, partition_key, database-assigned sequence, attempts, next_attempt_at, trace_information, published_at)
- `EventSourcingTable`: Complete event history by rootId for event sourcing

**Pattern:** Events are persisted atomically with business data in the same transaction. A background coroutine sweeps buckets, locks one bucket at a time and delegates its events, one transaction each, to a chain of Publishers.

### event-rabbit (RabbitMQ Integration)
Provides RabbitMQ distribution with retry/dead-letter queue handling.

**Key components:**
- `RabbitPublisher`: Publishes events to RabbitMQ topic exchange with trace headers. Waits for the
  publisher confirm before returning (a nack or a timeout throws, so the outbox keeps the event, and
  the optional `errorHandler` sees the event and the failure before it is rethrown),
  serializes publishes on one channel with a mutex (a confirm covers everything unconfirmed on the
  channel, not one message) and reopens connection and channel after a failure. `AutoCloseable`
- `RabbitQueue`: Consumer manager with Dead Letter Exchange (DLX) support. Routing keys are
  `Event.Key.name`. Every subscriber it starts registers its key in the `EventRegistry` it holds —
  what an application consumes is exactly the keys of its subscribers, so the consuming side needs no
  registration by hand, only what the application publishes does. A name the registry already holds is
  left alone — a key registered by hand is the one whose serializer cannot be taken off its class, and
  asking again would fail the start of a consumer that is set up correctly. Events with no declared
  name are logged once at start. A consumer that ends without being asked to — cancelled by the broker
  (deleted queue, lost node) or taken down with an error of its channel — is rebuilt from scratch —
  fresh channel, declarations and bindings — at one attempt per 5s until the broker accepts it, the
  successful attempt paced too so a queue that cancels its consumer over and over can not churn
  channels. A connection level failure is left to the automatic recovery of the amqp client, which
  restores consumers itself; rebuilding on top of it would leave two consumers on the queue
- `Config.bindLegacyRoutes`: Binds a queue to the route an event was published under before it
  declared a name, on top of the declared one, so a rolling deploy does not drop the events of a
  publisher that is still on the old route. On by default and deprecated; turn it off once every
  publisher emits declared names, a `BoundRoutes` then unbinds the legacy routes on the next start
- `ListenerDeliveryCallback`: Handles message delivery with automatic retry logic; a queue without
  retries (`maxRetries == 0`) holds a failing delivery unacked for `retryDelay` before the
  requeueing nack, so the redelivery loop is paced instead of hot
- `QueueConfig`: Configuration for queue behavior (prefetch, maxRetries, retryDelay)
- `BoundRoutes`: The routes a queue is bound to, handed to `RabbitQueue` to unbind on start
  everything bound to the queue from the exchange that no subscriber asks for any more — `queueBind`
  only ever adds, so a dropped subscription otherwise keeps its route forever. Off by default (null),
  and it unbinds routes bound by hand too. `ManagementApi` is the implementation, over the HTTP API
  of the management plugin, because AMQP 0-9-1 has no command that lists bindings; the unbinding
  itself is AMQP, so read access is enough. Failures are logged, the consumer starts either way
- `ConsumerMetrics`: The consumer side of the observability, handed to `RabbitQueue` — time a delivery
  waited for a free worker and time the subscriber ran, and every ending other than an ack (`failed`
  followed by `retried`, `parked` or `requeued`, plus `noSubscriber`) for the application registry
  (default `NoOpConsumerMetrics`)

**Retry mechanism:** 3-queue architecture per subscriber (Main Queue → DLX → Retry Queue → Main Queue → Parking Lot after max retries)

### event-ksp (Compile Time Event Catalog)
KSP processor generating the `EventCatalog` of a module: the key of every event of it, plus the
`META-INF/services` entry `EventRegistry.discovered()` loads it by. The catalog is exhaustive rather
than a list of the events that opted in — a payload carries the name of its event whether that name
is declared or derived, so an event missing from every catalog is an event whose rows nothing can
read. That is why the two shapes a catalog cannot carry are build errors, not warnings: an event that
is not visible outside its file, and one whose key is not its companion object. Both are fixed on the
spot (make it `internal`, give it a companion key) or registered by hand with
`EventRegistry.register(key, serializer)`, which is also what events from jars compiled without the
processor need. A `key` overridden with a backing field stays a warning — it is about the payload,
not the catalog.
The catalog class name is derived from the hash of the event set, so catalogs of different modules
never collide on one classpath. Applied to the test sources of `event` as its own end-to-end test
(`kspTest(project(":event-ksp"))`), see `EventCatalogTest`.

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
@Serializable
class MyEvent(val data: String) : Event() {
    override val key get() = Companion
    companion object : Key<MyEvent> {
        override val name = "billing.subscription.created"
    }
}
```

### Stable Event Names
The name of an event is declared, never derived. It is the routing key it is published under and the
`type` of every row it is stored as, and both outlive the class: a package refactoring used to change
the routing key of an event, orphaning the bindings of the queues consuming it, and to make every
`outbox_events` and `event_sourcing` row of it unreadable — for event sourcing, where the rows live
forever, permanently.

- The name is on `Event.Key`, the identity routing and subscribing already go through. An event that
  declares nothing falls back to the name derived from its class — as fragile as the class, but the
  same mechanism: one name, routed, stored and registered. There is no second path through the code
  for a non-migrated event, only a less stable name.
- Reading a name back needs an `EventRegistry`, built once at startup and handed to the `Outbox`, the
  `EventSourcingStorage`, the `RabbitQueue` and the `RabbitPublisher`. The table is maintained by the
  compiler: the `event-ksp` processor writes an `EventCatalog` of every event of the module, and
  `EventRegistry.discovered()` folds the catalogs on the classpath into the registry — there is no
  registration to forget:
  ```kotlin
  val events = EventRegistry.discovered()
  Outbox(bucketCount = 16, events = events)
  ```
  The processor is applied per module (`alias(deps.plugins.ksp)` + `ksp("com.turbomates:event-ksp:..")`),
  and applying it is not optional: an event in a module compiled without it reaches no catalog, and its
  rows are undecodable — the one hole the compiler cannot report. It fails the build on an event that
  is not visible outside its file and on one whose key is not its companion object, both of which are
  registered by hand instead (`register(key, serializer)`), which also covers events from jars compiled
  without the processor. A `key` overridden with a backing field stays a warning — it would enter the
  payload and fail the write, override it with a getter. What the application consumes additionally
  registers itself out of the subscribers of `RabbitQueue`.
- Declaring a name changes the routing key of that event and the `type` of the rows written from then
  on. `Config.bindLegacyRoutes` keeps the old route bound for the length of a rolling deploy; the rows
  written before it keep being read by their class name, which means the class has to stay where it is
  until they are gone — or their `type` is migrated:
  ```sql
  UPDATE event_sourcing SET data = jsonb_set(data, '{type}', '"billing.subscription.created"')
  WHERE data->>'type' = 'com.turbomates.billing.subscription.SubscriptionCreated';
  ```
- Upgrading to a version that writes the name into the payload changes the stored `type` of an event
  that declares nothing too — from the qualified class name to the derived name, its routing key all
  along. Nothing is lost: the rows already written keep being read by their class. What it costs is a
  rolling deploy window in which a node of the older version cannot decode a row a new one wrote (an
  outbox row is deferred and goes out once the deploy is over), and a second historical `type` to
  migrate the day that event declares a name.

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
val events = EventRegistry.discovered()  // the catalogs generated by event-ksp
val publishers = listOf(
    LocalPublisher(registry),                  // In-process subscribers
    RabbitPublisher(config, events)             // Distributed messaging
)
val outbox = Outbox(bucketCount = 16, events = events)
val outboxPublisher = OutboxPublisher(database, publishers, outbox.also { it.install() })
```

## Testing

- Tests use JUnit Platform (kotlin.test)
- Coroutine tests use `runBlocking` wrapper
- Integration tests use Testcontainers (postgres, rabbitmq)
- Test files follow pattern: `*Test.kt` in `src/test/kotlin`

## Code Quality

- Static analysis: detekt with configuration in `detekt.yml`
- Formatting enforced via detekt-formatting plugin
- detekt 1.23 runs inside the gradle daemon and its embedded compiler throws on a `java.version`
  of 25, so the detekt tasks need a daemon on a JDK of 24 or older
  (`./gradlew detekt -Dorg.gradle.java.home=<jdk24>`). For that reason `check` does not depend on
  `detekt` and `./gradlew build` does not lint
- PR checks run via reviewdog GitHub Action, that job pins JDK 24
- Max issues set to 100000 in CI (via `yq -i '.build.maxIssues = 100000' detekt.yml`)

## Build Configuration

- Gradle version catalog in `settings.gradle.kts` under `deps` namespace
- Java 25 required
- Kotlin 2.4.10
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