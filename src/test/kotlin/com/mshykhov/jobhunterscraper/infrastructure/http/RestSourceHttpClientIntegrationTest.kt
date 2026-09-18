package com.mshykhov.jobhunterscraper.infrastructure.http

import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RestSourceHttpClientIntegrationTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `retries 429 and honors a bounded Retry-After`() {
        val requests = AtomicInteger()
        val queries = mutableListOf<String?>()
        server.createContext("/retry") { exchange ->
            synchronized(queries) { queries += exchange.requestURI.rawQuery }
            if (requests.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(429, -1)
                exchange.close()
            } else {
                exchange.respond(200, "ok")
            }
        }
        val client = client(ScraperProperties(httpAttempts = 2, retryDelay = Duration.ZERO))

        assertEquals("ok", client.get("$baseUrl/retry?search=Kotlin&page=2"))
        assertEquals(2, requests.get())
        assertEquals(listOf("search=Kotlin&page=2", "search=Kotlin&page=2"), queries)
    }

    @Test
    fun `caps response body before materializing it`() {
        server.createContext("/large") { it.respond(200, "12345") }
        val client = client(ScraperProperties(httpAttempts = 1, maxResponseBytes = 4))

        assertThrows(ResponseTooLargeException::class.java) { client.get("$baseUrl/large") }
    }

    @Test
    fun `transport errors and observations omit query credentials`() {
        server.createContext("/slow") { exchange ->
            Thread.sleep(100)
            runCatching { exchange.respond(200, "late") }
        }
        val observedValues = mutableListOf<String>()
        val observedErrors = mutableListOf<String>()
        val registry =
            ObservationRegistry.create().also { observationRegistry ->
                observationRegistry.observationConfig().observationHandler(
                    object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context): Boolean = true

                        override fun onStop(context: Observation.Context) {
                            context.highCardinalityKeyValues.forEach { observedValues += it.value }
                            context.error?.message?.let(observedErrors::add)
                        }
                    },
                )
            }
        val properties = ScraperProperties(httpAttempts = 1, httpTimeout = Duration.ofMillis(20))
        val client = client(properties, registry)

        val failure =
            assertThrows(SourceTransportException::class.java) {
                client.get("$baseUrl/slow?proxy=http://user:password@proxy.test:8080")
            }

        assertFalse(failure.message.orEmpty().contains("password"))
        assertFalse(observedValues.any { it.contains("password") || it.contains("proxy=") })
        assertFalse(observedErrors.any { it.contains("password") || it.contains("proxy=") })
        assertTrue(observedValues.any { it.endsWith("/slow") })
    }

    private fun client(
        properties: ScraperProperties,
        registry: ObservationRegistry = ObservationRegistry.NOOP,
    ): RestSourceHttpClient =
        RestSourceHttpClient(
            RestClient
                .builder()
                .observationRegistry(registry)
                .observationConvention(SanitizedClientRequestObservationConvention()),
            properties,
        )

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
