# Kotlin Events

A Kotlin library for building event-driven architectures with support for transactional outbox pattern, RabbitMQ messaging, and distributed tracing.

## Features

- **Event-Driven Architecture**: Type-safe event publishing and subscription with Kotlin coroutines
- **Transactional Outbox Pattern**: Ensure atomicity between business data and event publishing
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

// Register global interceptor
GlobalStatementInterceptor.register(OutboxInterceptor())

// Start outbox publisher
val publishers = listOf(
    LocalPublisher(registry),
    // Add other publishers as needed
)

val outboxPublisher = OutboxPublisher(
    database = database,
    publishers = publishers,
    batchSize = 100,
    delay = 1000L // Poll every 1 second
)

outboxPublisher.start()
```

**How it works:**

1. Events are persisted to the database in the same transaction as your business data
2. A background worker polls unpublished events
3. Events are published to all configured publishers
4. Ensures no events are lost even if the application crashes

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
    GlobalStatementInterceptor.register(OutboxInterceptor())
    val outboxPublisher = OutboxPublisher(database, publishers)
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
- Java 21+
- PostgreSQL (for event-exposed module)
- RabbitMQ (for event-rabbit module)

## License

MIT License - see [LICENSE.md](LICENSE.md) for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

- **Issues**: [GitHub Issues](https://github.com/turbomates/kotlin-events/issues)
- **Documentation**: See [CLAUDE.md](CLAUDE.md) for development guidelines