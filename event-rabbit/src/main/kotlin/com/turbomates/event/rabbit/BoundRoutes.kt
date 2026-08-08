package com.turbomates.event.rabbit

import com.rabbitmq.client.ConnectionFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The routing keys a queue is currently bound to on an exchange, the set [RabbitQueue] compares
 * its subscribers against to find the bindings left over by a subscription that is gone.
 *
 * AMQP 0-9-1 has no command that lists bindings, so an implementation has to look outside the
 * protocol — [ManagementApi] asks the RabbitMQ HTTP management API.
 *
 * The set belongs to the queue, not to the instance that starts: during a rolling deploy the new
 * version unbinds what it dropped while the old one is still consuming the queue, so events of a
 * dropped subscription stop reaching the old version as soon as the first new instance is up.
 */
fun interface BoundRoutes {
    fun of(queue: String, exchange: String): Set<String>
}

/**
 * [BoundRoutes] over the HTTP API of the RabbitMQ management plugin, which has to be enabled on
 * the broker. The user needs the `monitoring` tag (or `management` plus permissions on the vhost)
 * to read `/api/queues/{vhost}/{queue}/bindings`; the unbinding itself happens over AMQP, so no
 * write access to the API is required.
 */
class ManagementApi(
    url: String,
    username: String,
    password: String,
    private val vhost: String = "/",
    private val timeout: Duration = DEFAULT_TIMEOUT
) : BoundRoutes {
    private val url = url.trimEnd('/')
    private val credentials = Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
    private val client = HttpClient.newBuilder().connectTimeout(timeout.toJavaDuration()).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun of(queue: String, exchange: String): Set<String> {
        val request = HttpRequest
            .newBuilder(URI.create("$url/api/queues/${vhost.segment()}/${queue.segment()}/bindings"))
            .header("Authorization", "Basic $credentials")
            .header("Accept", "application/json")
            .timeout(timeout.toJavaDuration())
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == OK) {
            "Management API of $url answered ${response.statusCode()} for the bindings of $queue"
        }
        return json.decodeFromString<List<Binding>>(response.body())
            .filter { it.source == exchange && it.destinationType == QUEUE }
            .map { it.routingKey }
            .toSet()
    }

    @Serializable
    private data class Binding(
        val source: String,
        @SerialName("routing_key") val routingKey: String,
        @SerialName("destination_type") val destinationType: String
    )

    companion object {
        const val DEFAULT_PORT = 15672
        val DEFAULT_TIMEOUT = 10.seconds
        private const val OK = 200
        private const val QUEUE = "queue"

        /**
         * The host, credentials and vhost of [factory], with the port of the management plugin,
         * which is a different one from the AMQP port of the factory.
         */
        fun of(
            factory: ConnectionFactory,
            port: Int = DEFAULT_PORT,
            scheme: String = "http",
            timeout: Duration = DEFAULT_TIMEOUT
        ): ManagementApi = ManagementApi(
            "$scheme://${factory.host}:$port",
            factory.username,
            factory.password,
            factory.virtualHost,
            timeout
        )
    }
}

// URLEncoder writes a space as '+', which is a literal '+' in a path segment.
private fun String.segment(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
