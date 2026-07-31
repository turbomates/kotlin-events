# Kotlin Events

A Kotlin library for building event-driven architectures with support for transactional outbox pattern, RabbitMQ messaging, and distributed tracing.

## Features

- **Event-Driven Architecture**: Type-safe event publishing and subscription with Kotlin coroutines
- **Transactional Outbox Pattern**: Ensure atomicity between business data and event publishing
- **Partitioned Outbox**: Events are spread over buckets so several workers publish in parallel while
  events of one partition key stay in order
- **RabbitMQ Integration**: Distributed event messaging with automatic retry and dead-letter queue handling
- **Distributed Tracing**: OpenTelemetry integration with W3C trace context propagation
- **Event Sourcing**: Built-in support for event sourcing patterns
- **Type Safety**: Leverage Kotlin's type system for compile-time safety

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    // Core event library
    implementation("com.turbomates:event:VERSION")

    // Outbox pattern with Exposed ORM (optional)
    implementation("com.turbomates:event-exposed:VERSION")

    // RabbitMQ integration (optional)
    implementation("com.turbomates:event-rabbit:VERSION")

    // OpenTelemetry tracing (optional)
    implementation("com.turbomates:event-telemetry-opentelemetry:VERSION")
}
```

Replace `VERSION` with the latest version from [Maven Central](https://search.maven.org/search?q=g:com.turbomates%20AND%20a:event*).

## Quick Start

### 1. Define an Event

```kotlin
import com.turbomates.event.Event

class OrderCreated(
    val orderId: String,
    val amount: Double
) : Event() {
    override val key: Key<out Event> = Companion
    companion object : Key<OrderCreated>
}
```

An event can name the stream it belongs to with `partitionKey`. The outbox keeps events of one
partition key in the same bucket, so they are published by one worker, in order, while other buckets
are published in parallel. Override it with a getter, the property is `@Transient` and never reaches
the payload:

```kotlin
@Serializable
data class AccountTransactionApplied(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @Serializable(with = UUIDSerializer::class) val userId: UUID
) : Event() {
    override val key get() = Companion
    override val partitionKey get() = userId

    companion object : Key<AccountTransactionApplied>
}
```

Events without a partition key fall back to their own outbox id and spread evenly over the buckets.

### 2. Create a Subscriber

```kotlin
import com.turbomates.event.EventSubscriber

class OrderCreatedSubscriber : EventSubscriber<OrderCreated>() {
    override suspend fun invoke(event: OrderCreated) {
        println("Order ${event.orderId} created with amount ${event.amount}")
        // Process the event
    }
}
```

### 3. Set Up Publisher and Registry

```kotlin
import com.turbomates.event.LocalPublisher
import com.turbomates.event.SubscribersRegistry

val registry = SubscribersRegistry()
registry.registry(OrderCreatedSubscriber())

val publisher = LocalPublisher(registry)
```

### 4. Publish Events

```kotlin
import kotlinx.coroutines.runBlocking

runBlocking {
    publisher.publish(OrderCreated("123", 99.99))
}
```

## Modules

### event (Core)

The foundation module providing core event-driven abstractions.

**Key Components:**
- `Event`: Base class for all events
- `Publisher`: Interface for event publication
- `LocalPublisher`: In-process event publisher
- `EventSubscriber`: Handler for single event type
- `SubscribersRegistry`: Type-safe event routing

### event-exposed (Outbox Pattern)

Implements the transactional outbox pattern using Exposed ORM for PostgreSQL.

**Setup:**

```kotlin
import com.turbomates.event.exposed.OutboxInterceptor
import com.turbomates.event.exposed.OutboxPublisher
import org.jetbrains.exposed.sql.Database

// Initialize database
val database = Database.connect(
    url = "jdbc:postgresql://localhost:5432/mydb",
    driver = "org.postgresql.Driver",
    user = "user",
    password = "password"
)

// Describe the outbox and register its interceptor for every transaction
val outbox = Outbox(
    bucketCount = 16,
    batchLimit = 100                     // events per bucket, not per sweep
)
outbox.install()

// Start outbox publisher
val publishers = listOf(
    LocalPublisher(registry),
    // Add other publishers as needed
)

val outboxPublisher = OutboxPublisher(
    database = database,
    publishers = publishers,
    outbox = outbox,
    delay = Duration.parse("1s")         // pause between sweeps
)

outboxPublisher.start()
```

**How it works:**

1. Events are persisted to the database in the same transaction as your business data
2. Every row gets a `bucket`, derived from the event `partitionKey` or from the row id
3. A background worker sweeps the buckets one by one, starting at a rotating position
4. A bucket is taken with a non blocking lock and held for the whole batch, buckets held by another
   worker are skipped
5. Every event is published in its own transaction, which deletes the row first and commits after the
   publishers are done, so a failure costs one redelivery and never the whole batch
6. Ensures no events are lost even if the application crashes

**Buckets:**

`Outbox(bucketCount)` is required and has no default. It describes the rows already written to
`outbox_events`, not a deployment: changing it re-maps every partition key, so events of one stream
would sit in two buckets at once and could be published by two workers in parallel. Pick it once, keep
it in code next to the other constants of the application, and change it only by draining the outbox
first. Every process of the application builds its `Outbox` with the same count, and the one instance
is handed to both `install()` and the publisher.

`batchLimit` is per bucket: a sweep publishes up to `batchLimit * bucketCount` events. It only bounds
how much one worker takes from a bucket per sweep, the publishing transaction stays one event wide.
It belongs to the `Outbox` together with the bucket count, the publisher only decides how often it
sweeps.

**Several workers:**

Buckets are locked with `pg_try_advisory_xact_lock`. The lock is taken by a transaction that does
nothing but hold it for the batch, and the database releases it when that transaction ends, so a
crashed worker never blocks its bucket. The events themselves are published in transactions of their
own, which means a worker holds two connections while it works on a bucket, plan the pool for it. Advisory locks are a Postgres feature, other
databases can plug their own implementation of `OutboxBucketLock` (or use `SingleWorkerBucketLock`
when a single worker publishes the outbox):

```kotlin
val outbox = Outbox(
    bucketCount = 16,
    bucketLock = PostgresAdvisoryBucketLock(namespace = 42)
)

OutboxPublisher(database = database, publishers = publishers, outbox = outbox)
```

**Metrics:**

`OutboxMetrics` reports per bucket how many buckets the worker actually holds, the age of the oldest
unpublished event, and how many events were published, skipped or failed. `outboxDepth` is the total
unpublished backlog, measured on its own ticker (`depthInterval` of the publisher, 10s by default)
so it stays fresh even while a sweep drowns in a deep backlog — its slope is the drain rate, a
growing value means the writers are ahead of the workers. It is a seam for the
application metrics registry, nothing more, the publisher logs its own errors on its own. The default
is `NoOpOutboxMetrics`, every method of the interface has an empty default, so an implementation only
overrides what it exports:

```kotlin
class PrometheusOutboxMetrics(private val registry: MeterRegistry) : OutboxMetrics {
    override fun bucketAcquired(bucket: Int, pending: Int, lag: Duration) {
        registry.gauge("outbox.lag.seconds", listOf(Tag.of("bucket", bucket.toString())), lag.inWholeSeconds)
    }

    override fun sweepCompleted(ownedBuckets: Int, totalBuckets: Int) {
        registry.gauge("outbox.buckets.owned", ownedBuckets)
    }
}
```

**Failing events, backoff and order:**

A publisher that throws rolls the deletion of the row back, so the event is never lost — but it is
not hammered either. Every failure increments `attempts` and sets `next_attempt_at` to
`now() + backoff` (database time), and until that moment the whole stream of the event is skipped by
the sweeps: nothing is published out of order with its partition, the other streams of the bucket
continue. The same applies to a row that can no longer be decoded — an unknown event type
mid-rollout, for example — it costs one failed attempt of its own stream, not the bucket.

The backoff is exponential and belongs to the `Outbox`:

```kotlin
val outbox = Outbox(
    bucketCount = 16,
    retryPolicy = OutboxRetryPolicy(
        initialDelay = 1.seconds, // attempt N waits initialDelay * multiplier^(N-1)
        multiplier = 2.0,
        maxDelay = 5.minutes
    )
)
```

There is no attempt limit and no dead-letter table on purpose: giving an event up would silently
break the order of its stream, and that is a decision the application has to make, not the library.
A poison event is almost always a bug — a subscriber that chokes on the data, a serializer that lost
a type — and once the fix is deployed the event publishes on the next try and its stream drains
itself, in order, with no manual repair. Alert on `OutboxMetrics.eventFailed` (its `attempts` keeps
growing while nobody looks); the truly unrecoverable row is removed by hand:
`DELETE FROM outbox_events WHERE id = '...'`, which releases its stream.

Events are published in the order they were raised: the order inside a transaction is kept by the
`EventStore`, the order in the table by the database-assigned `sequence` column — client timestamps
are neither unique nor monotonic across pods, so they are data, not ordering.

**Custom serialization:**

The format of the `jsonb` columns comes with the outbox, build the `Json` on top of
`EventSerialization.DEFAULT_JSON` to add a serializers module of your own, for example contextual
serializers of the value types the events carry, and keep the flags the existing rows were written
with:

```kotlin
val serialization = EventSerialization(
    Json(from = EventSerialization.DEFAULT_JSON) { serializersModule = domainSerializers }
)

val outbox = Outbox(bucketCount = 16, serialization = serialization)
val storage = EventSourcingStorage(database, serialization)
```

The `Outbox` keeps its serialization to itself, so build the `EventSerialization` once and hand the
same instance to everything that reads those columns.

The second parameter of `EventSerialization` is the `KSerializer<Event>` that decides what a row looks
like, by default `EventSerializer` and its `{"type": <class>, "body": {...}}`. A table that already
holds rows can only be read back by a serializer that understands them, so replacing it is a
migration, not a setting.

**Schema:**

`event-exposed` ships `outbox_events_postgres_table.sql` for a new database. One that already runs
the released schema is migrated with (16 is the `bucketCount` the application is built with):

```sql
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS bucket integer;
-- 16 is the bucketCount the application is built with
UPDATE outbox_events SET bucket = mod(abs(hashtext(id::text)), 16) WHERE bucket IS NULL;
ALTER TABLE outbox_events ALTER COLUMN bucket SET NOT NULL;

ALTER TABLE outbox_events ADD COLUMN partition_key uuid;
UPDATE outbox_events SET partition_key = id WHERE partition_key IS NULL;
ALTER TABLE outbox_events ALTER COLUMN partition_key SET NOT NULL;

ALTER TABLE outbox_events ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE outbox_events ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN next_attempt_at timestamp with time zone;

DROP INDEX IF EXISTS events_publisshed_idx;
DROP INDEX IF EXISTS outbox_events_bucket_idx;
CREATE INDEX outbox_events_bucket_idx ON outbox_events (bucket, sequence) WHERE published_at IS NULL;
CREATE INDEX outbox_events_blocked_idx ON outbox_events (bucket, partition_key) WHERE next_attempt_at IS NOT NULL;
```

Run it on a drained outbox. The backfills only approximate what the new build writes — the real
partition key of an old row was never persisted: backfilled rows are spread over the buckets by
their id and every old row becomes a stream of its own, so a backlog left in the table may replay
out of order once (the identity column also numbers the existing rows in arbitrary order). The rows
written by the new build get their real partition keys from the first insert.

**Event Sourcing:**

```kotlin
import com.turbomates.event.exposed.EventSourcingEvent
import com.turbomates.event.exposed.EventSourcingStorage

class OrderEvent(
    override val rootId: String,
    val data: String
) : EventSourcingEvent() {
    override val key: Key<out Event> = Companion
    companion object : Key<OrderEvent>
}

// Retrieve event history for an aggregate
val storage = EventSourcingStorage(database)
val events = storage.get("order-123")
```

### event-rabbit (RabbitMQ)

Provides RabbitMQ integration with automatic retry and dead-letter queue handling.

**Setup:**

```kotlin
import com.turbomates.event.rabbit.RabbitPublisher
import com.turbomates.event.rabbit.RabbitQueue
import com.turbomates.event.rabbit.Config
import com.turbomates.event.rabbit.QueueConfig
import com.rabbitmq.client.ConnectionFactory

val connectionFactory = ConnectionFactory().apply {
    host = "localhost"
    port = 5672
    username = "guest"
    password = "guest"
}

val config = Config(
    factory = connectionFactory,
    exchange = "events",
    queuePrefix = "myapp"
)

// Publisher
val rabbitPublisher = RabbitPublisher(config)

// Consumer
val queueConfig = QueueConfig(
    prefetch = 10,
    maxRetries = 3,
    retryDelay = 5000L // 5 seconds
)

val queue = RabbitQueue(config, registry, queueConfig)
queue.consume()
```

**Retry Mechanism:**

- Failed messages are sent to a dead-letter exchange
- Messages wait for TTL (configurable retry delay)
- Messages are redelivered up to `maxRetries` times
- After max retries, messages move to a parking lot queue for manual review

**Consumer metrics:**

`OutboxMetrics` measures the publishing end, `ConsumerMetrics` the other one: what the queue actually
did with a delivery. It is the same kind of seam — a `NoOpConsumerMetrics` default, every method with
an empty body, so an implementation only overrides what it exports — and it is passed to the queue,
not to a global registry:

```kotlin
val queue = RabbitQueue(
    config,
    json,
    registry,
    scope = applicationScope,
    telemetryService = telemetry,
    metrics = PrometheusConsumerMetrics(meterRegistry)
)
```

```kotlin
class PrometheusConsumerMetrics(private val registry: MeterRegistry) : ConsumerMetrics {
    override fun handled(queue: String, routingKey: String, waited: Duration, took: Duration) {
        registry.timer("rabbit.consumer.took", "queue", queue).record(took.toJavaDuration())
        registry.timer("rabbit.consumer.waited", "queue", queue).record(waited.toJavaDuration())
    }

    override fun parked(queue: String, routingKey: String, retries: Long) {
        registry.counter("rabbit.consumer.parked", "queue", queue).increment()
    }
}
```

Both label sets are bounded — the queue name and the routing key of the event, nothing per message.

`handled` splits the delay the consumer adds by itself (`waited`) from the work (`took`), so a queue
starved of workers looks different from a slow subscriber. That wait is worth watching, because the
broker can not show it: it hands over up to `prefetchCount` messages at once and they queue in memory
until one of `maxConcurrency` workers is free, counting as unacked on the broker's side — the queue
looks empty there while the backlog sits in the process.

`failed` is followed by exactly one of `retried`, `parked` or `requeued`, which tells what happened to
the delivery. `noSubscriber` counts messages routed to a queue whose consumer has no subscriber for
their key — they are acked and dropped, and this is the only trace they leave.

Alert on `parked`: the retries are over, the message is in the `_pl` queue and nothing takes it out
but a human.

Implementations are expected not to throw — they sit next to the ack of the delivery, `handled` right
between it and the subscriber — but one that does costs nothing but a log line: the callback catches
it and settles the delivery as if the metric had returned.

### event-telemetry-opentelemetry (Distributed Tracing)

OpenTelemetry implementation for distributed tracing across services.

**Setup:**

```kotlin
import com.turbomates.event.telemetry.opentelemetry.OpenTelemetryService
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer

// Configure OpenTelemetry (example with OTLP exporter)
val openTelemetry = GlobalOpenTelemetry.get()
val tracer = openTelemetry.getTracer("my-service")

// Service is auto-discovered via Java ServiceLoader
// Just include the dependency and it will be used automatically
```

**How it works:**

- W3C trace context is automatically propagated through events
- Trace information flows: Application → Outbox → RabbitMQ → Consumer
- Each step creates child spans for distributed trace visibility
- Falls back to no-op if OpenTelemetry is not configured

## Complete Example

```kotlin
import com.turbomates.event.*
import com.turbomates.event.exposed.*
import com.turbomates.event.rabbit.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

// 1. Define events
class UserRegistered(val userId: String, val email: String) : Event() {
    override val key: Key<out Event> = Companion
    companion object : Key<UserRegistered>
}

// 2. Create subscribers
class UserRegisteredSubscriber : EventSubscriber<UserRegistered>() {
    override suspend fun invoke(event: UserRegistered) {
        println("Sending welcome email to ${event.email}")
    }
}

// 3. Setup
fun main() = runBlocking {
    // Database
    val database = Database.connect(
        url = "jdbc:postgresql://localhost:5432/mydb",
        driver = "org.postgresql.Driver"
    )

    // Registry
    val registry = SubscribersRegistry()
    registry.registry(UserRegisteredSubscriber())

    // Publishers
    val rabbitConfig = Config(/* ... */)
    val publishers = listOf(
        LocalPublisher(registry),
        RabbitPublisher(rabbitConfig)
    )

    // Outbox
    val outbox = Outbox(bucketCount = 16)
    outbox.install()
    val outboxPublisher = OutboxPublisher(database, publishers, outbox)
    outboxPublisher.start()

    // Consumer
    val queue = RabbitQueue(rabbitConfig, registry, QueueConfig())
    queue.consume()

    // 4. Publish events within transactions
    transaction(database) {
        // Your business logic
        val userId = createUser("user@example.com")

        // Raise event - will be persisted atomically
        EventStore.addEvent(UserRegistered(userId, "user@example.com"))
    }
    // Event is now in outbox and will be published by background worker
}
```

## Architecture

```
┌─────────────────────────────────────────┐
│           event (Core)                   │
│ Event, Publisher, TelemetryService       │
└────────────────────┬────────────────────┘
                     │
     ┌───────────────┼───────────────────┐
     │               │                   │
     v               v                   v
┌──────────┐  ┌──────────┐  ┌──────────────────┐
│  event-  │  │  event-  │  │ event-telemetry- │
│ exposed  │  │  rabbit  │  │ opentelemetry    │
│          │  │          │  │                  │
│ Outbox   │  │ RabbitMQ │  │ OpenTelemetry    │
│ Pattern  │  │ Integration│ │ Tracing         │
└──────────┘  └──────────┘  └──────────────────┘
```

## Benefits

### Outbox Pattern
- **Atomicity**: Events are persisted with business data in the same transaction
- **Reliability**: No events are lost even if the application crashes
- **Resilience**: Failed publisher calls are automatically retried
- **Consistency**: Guarantees eventual consistency across services

### RabbitMQ Integration
- **Scalability**: Distribute events across multiple consumers
- **Retry Logic**: Automatic retry with configurable delays
- **Dead Letter Queues**: Failed messages move to parking lot for manual review
- **Flow Control**: Configurable prefetch for back-pressure

### Distributed Tracing
- **Observability**: End-to-end trace visibility across services
- **Standards-based**: W3C trace context propagation
- **Correlation**: Track related operations across service boundaries
- **Debugging**: Identify bottlenecks and errors in distributed systems

## Requirements

- Kotlin 2.2.20+
- Java 25+
- PostgreSQL (for event-exposed module)
- RabbitMQ (for event-rabbit module)

## License

MIT License - see [LICENSE.md](LICENSE.md) for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

- **Issues**: [GitHub Issues](https://github.com/turbomates/kotlin-events/issues)
- **Documentation**: See [CLAUDE.md](CLAUDE.md) for development guidelines