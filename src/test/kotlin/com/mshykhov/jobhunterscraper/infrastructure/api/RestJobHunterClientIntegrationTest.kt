package com.mshykhov.jobhunterscraper.infrastructure.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.RunClaim
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.JobHunterProperties
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RestJobHunterClientIntegrationTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val objectMapper = jacksonObjectMapper()

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
    fun `caches token and refreshes it once after 401`() {
        val tokenRequests = AtomicInteger()
        val claimRequests = AtomicInteger()
        server.createContext("/token") { exchange ->
            val form = exchange.formBody()
            assertEquals("client_credentials", form["grant_type"])
            assertEquals("client", form["client_id"])
            assertEquals("service", form["username"])
            assertEquals("app-password", form["password"])
            assertEquals("profile job-hunter-api", form["scope"])
            val token = "token-${tokenRequests.incrementAndGet()}"
            exchange.respond(
                200,
                """{"access_token":"$token","expires_in":300,"token_type":"Bearer","scope":"profile","id_token":"opaque"}""",
            )
        }
        server.createContext("/scraping/sources/dou/claim") { exchange ->
            claimRequests.incrementAndGet()
            if (exchange.requestHeaders.getFirst("Authorization") == "Bearer token-1") {
                exchange.respond(401, "sensitive upstream body")
            } else {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
        }
        val client = client(authProperties())

        assertNull(client.claim(JobSource.DOU))
        assertNull(client.claim(JobSource.DOU))
        assertEquals(2, tokenRequests.get())
        assertEquals(3, claimRequests.get())
    }

    @Test
    fun `retries transient batch response with the same idempotency key`() {
        val receivedBatchIds = mutableListOf<String>()
        val requests = AtomicInteger()
        server.createContext("/scraping/runs") { exchange ->
            val body = objectMapper.readValue<Map<String, Any?>>(exchange.requestBody)
            synchronized(receivedBatchIds) { receivedBatchIds += body.getValue("batchId").toString() }
            if (requests.incrementAndGet() == 1) {
                exchange.respond(503, "temporarily unavailable")
            } else {
                exchange.respond(200, """{"acceptedCount":1}""")
            }
        }
        val properties =
            JobHunterProperties(
                apiUrl = baseUrl,
                authEnabled = false,
                timeout = Duration.ofSeconds(1),
                attempts = 2,
                retryDelay = Duration.ZERO,
            )
        val client = client(properties)
        val claim = claim()
        val batchId = UUID.randomUUID()

        val accepted = client.batch(claim, batchId, listOf(job()), emptyMap(), 1)

        assertEquals(1, accepted)
        assertEquals(listOf(batchId.toString(), batchId.toString()), receivedBatchIds)
    }

    @Test
    fun `authentication errors never include credentials or response body`() {
        server.createContext("/token") { exchange ->
            exchange.respond(400, "app-password and provider details")
        }
        val properties = authProperties()
        val provider = AuthentikTokenProvider(RestClient.builder(), objectMapper, properties, FIXED_CLOCK)

        val failure = assertThrows(RuntimeException::class.java) { provider.token() }

        assertFalse(failure.message.orEmpty().contains("app-password"))
        assertFalse(failure.message.orEmpty().contains("provider details"))
    }

    @Test
    fun `malformed proxy payload does not escape response credentials`() {
        server.createContext("/proxies") { exchange ->
            exchange.respond(200, """{"url":"http://user:proxy-password@proxy.test","host":[]}""")
        }
        val client =
            client(
                JobHunterProperties(
                    apiUrl = baseUrl,
                    authEnabled = false,
                    attempts = 1,
                ),
            )

        val failure = assertThrows(ControlPlanePayloadException::class.java) { client.proxies(JobSource.WEB3CAREER) }

        assertFalse(failure.message.orEmpty().contains("proxy-password"))
        assertNull(failure.cause)
    }

    @Test
    fun `maps only lease error code to lease loss`() {
        val requests = AtomicInteger()
        server.createContext("/scraping/runs") { exchange ->
            val code = if (requests.incrementAndGet() == 1) "SCRAPING_LEASE_LOST" else "CONFLICT"
            exchange.respond(409, """{"message":"conflict","code":"$code"}""")
        }
        val client =
            client(
                JobHunterProperties(
                    apiUrl = baseUrl,
                    authEnabled = false,
                    attempts = 1,
                ),
            )
        val claim = claim()

        assertThrows(LeaseLostException::class.java) { client.complete(claim) }
        val conflict = assertThrows(ControlPlaneException::class.java) { client.complete(claim) }
        assertFalse(conflict is LeaseLostException)
    }

    private fun client(properties: JobHunterProperties): RestJobHunterClient {
        val tokenProvider = AuthentikTokenProvider(RestClient.builder(), objectMapper, properties, FIXED_CLOCK)
        return RestJobHunterClient(
            RestClient.builder(),
            tokenProvider,
            objectMapper,
            properties,
            ScraperProperties(workerId = "test-worker"),
        )
    }

    private fun authProperties() =
        JobHunterProperties(
            apiUrl = baseUrl,
            tokenUrl = "$baseUrl/token",
            clientId = "client",
            username = "service",
            password = "app-password",
            attempts = 1,
        )

    private fun claim() =
        RunClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            JobSource.DOU,
            SearchCriteria(listOf("JAVA")),
            emptyMap(),
            null,
            Instant.now().plusSeconds(300),
        )

    private fun job() =
        ScrapedJob(
            title = "Backend Engineer",
            url = "https://example.test/job",
            source = JobSource.DOU,
            category = "JAVA",
        )

    private fun HttpExchange.formBody(): Map<String, String> =
        requestBody
            .bufferedReader()
            .use { it.readText() }
            .split('&')
            .associate { pair ->
                val (key, value) = pair.split('=', limit = 2)
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC)
    }
}
