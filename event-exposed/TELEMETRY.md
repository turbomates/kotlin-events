# Telemetry Integration for OutboxInterceptor

## Обзор

Начиная с этой версии, `OutboxInterceptor` поддерживает сохранение контекста телеметрии (trace ID и span ID) при сохранении событий. Это позволяет отслеживать события через распределенную систему и связывать их с оригинальными запросами.

## Архитектура

Решение состоит из следующих компонентов:

### 1. TelemetryContext
Data class, содержащий trace ID и span ID:
```kotlin
data class TelemetryContext(
    val traceId: String?,
    val spanId: String?
)
```

### 2. TelemetryContextProvider
Интерфейс для извлечения текущего контекста телеметрии:
```kotlin
interface TelemetryContextProvider {
    fun getCurrentContext(): TelemetryContext
}
```

### 3. TelemetryContextHolder
Глобальный holder для провайдера телеметрии:
```kotlin
object TelemetryContextHolder {
    var provider: TelemetryContextProvider = NoOpTelemetryContextProvider
}
```

### 4. База данных
Таблица `outbox_events` теперь содержит два дополнительных поля:
- `trace_id` (text, nullable) - ID трейса для распределенной трассировки
- `span_id` (text, nullable) - ID спана текущей операции

## Использование

### Без телеметрии (по умолчанию)
По умолчанию используется `NoOpTelemetryContextProvider`, который возвращает пустой контекст. В этом случае `trace_id` и `span_id` будут null.

### С OpenTelemetry

1. Добавьте зависимость OpenTelemetry:
```kotlin
implementation("io.opentelemetry:opentelemetry-api:1.32.0")
```

2. Создайте провайдер:
```kotlin
import io.opentelemetry.api.trace.Span

class OpenTelemetryContextProvider : TelemetryContextProvider {
    override fun getCurrentContext(): TelemetryContext {
        val span = Span.current()
        val spanContext = span.spanContext

        return if (spanContext.isValid) {
            TelemetryContext(
                traceId = spanContext.traceId,
                spanId = spanContext.spanId
            )
        } else {
            TelemetryContext.EMPTY
        }
    }
}
```

3. Инициализируйте при старте приложения:
```kotlin
fun main() {
    // Настройка OpenTelemetry...

    // Установка провайдера телеметрии
    TelemetryContextHolder.provider = OpenTelemetryContextProvider()

    // Остальная инициализация приложения...
}
```

### С Micrometer Tracing

1. Добавьте зависимость Micrometer:
```kotlin
implementation("io.micrometer:micrometer-tracing:1.2.0")
```

2. Создайте провайдер:
```kotlin
import io.micrometer.tracing.Tracer

class MicrometerContextProvider(private val tracer: Tracer) : TelemetryContextProvider {
    override fun getCurrentContext(): TelemetryContext {
        val span = tracer.currentSpan()
        return if (span != null) {
            TelemetryContext(
                traceId = span.context().traceId(),
                spanId = span.context().spanId()
            )
        } else {
            TelemetryContext.EMPTY
        }
    }
}
```

3. Инициализируйте с бином Tracer:
```kotlin
@Bean
fun telemetryContextProvider(tracer: Tracer): TelemetryContextProvider {
    val provider = MicrometerContextProvider(tracer)
    TelemetryContextHolder.provider = provider
    return provider
}
```

## Миграция базы данных

Для существующих баз данных необходимо выполнить миграцию:

```sql
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS trace_id text,
    ADD COLUMN IF NOT EXISTS span_id text;
```

SQL-файл миграции находится в:
`event-exposed/src/main/resources/migrations/add_telemetry_columns.sql`

## Примеры использования

### Пример 1: Простое сохранение события
```kotlin
transaction {
    // Если телеметрия настроена, trace/span будут автоматически сохранены
    events.addEvent(UserCreatedEvent(userId = "123"))
}
```

### Пример 2: Восстановление контекста при публикации
```kotlin
class OutboxPublisher(/* ... */) {
    private fun publishEvent(event: PublicEvent, traceId: String?, spanId: String?) {
        // Можно восстановить контекст телеметрии при публикации
        if (traceId != null && spanId != null) {
            // Создать дочерний span с родительским контекстом
            // и опубликовать событие в этом контексте
        }

        publishers.forEach { it.publish(event.original) }
    }
}
```

## Тестирование

Пример теста с mock провайдером:

```kotlin
@Test
fun `should save telemetry context`() {
    TelemetryContextHolder.provider = object : TelemetryContextProvider {
        override fun getCurrentContext() = TelemetryContext(
            traceId = "test-trace-123",
            spanId = "test-span-456"
        )
    }

    try {
        transaction {
            events.addEvent(TestEvent())
        }

        transaction {
            val savedEvent = EventsTable.selectAll().first()
            assertEquals("test-trace-123", savedEvent[EventsTable.traceId])
            assertEquals("test-span-456", savedEvent[EventsTable.spanId])
        }
    } finally {
        TelemetryContextHolder.provider = NoOpTelemetryContextProvider
    }
}
```

## Производительность

- Извлечение контекста телеметрии происходит **один раз** для всего батча событий (в функции `save()`)
- Нет накладных расходов, если телеметрия не настроена (используется NoOpTelemetryContextProvider)
- Поля `trace_id` и `span_id` nullable, поэтому не требуют заполнения

## Совместимость

- Решение полностью обратно совместимо
- Существующий код работает без изменений
- Телеметрия опциональна и активируется только при настройке провайдера
- Поддерживает любую библиотеку телеметрии через интерфейс `TelemetryContextProvider`
