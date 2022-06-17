package com.turbomates.event.exposed

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject

fun <T : Any> Table.jsonb(
    name: String,
    serializer: KSerializer<T>,
    module: SerializersModule = EmptySerializersModule
): Column<T> {
    return registerColumn(name, PostgreSQLJson(serializer, module))
}

class PostgreSQLJson<out T : Any>(
    private val serializer: KSerializer<T>,
    module: SerializersModule = EmptySerializersModule
) : ColumnType() {
    private val json = Json {
        serializersModule = module
        encodeDefaults = true
    }

    override fun sqlType() = "jsonb"

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = value as String
        stmt[index] = obj
    }

    override fun notNullValueToDB(value: Any): Any {
        return nonNullValueToString(value)
    }

    override fun valueFromDB(value: Any): Any {
        if (value is PGobject) {
            return json.decodeFromString(serializer, value.value!!)
        }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    override fun nonNullValueToString(value: Any): String {
        return json.encodeToString(serializer, value as T)
    }

    override fun valueToString(value: Any?): String = when (value) {
        null -> {
            if (!nullable) error("NULL in non-nullable column")
            "NULL"
        }
        else -> {
            nonNullValueToString(value)
        }
    }
}
