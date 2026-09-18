package com.mshykhov.jobhunterscraper.infrastructure.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mshykhov.jobhunterscraper.application.model.JobCheckRequest
import com.mshykhov.jobhunterscraper.application.model.JobCheckResult
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.RunClaim
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.application.model.SourceProxy
import com.mshykhov.jobhunterscraper.infrastructure.config.JobHunterProperties
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.BoundedResponseReader
import com.mshykhov.jobhunterscraper.infrastructure.http.HttpRetrySupport
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

@Component
class RestJobHunterClient(
    restClientBuilder: RestClient.Builder,
    private val tokenProvider: AuthentikTokenProvider,
    private val objectMapper: ObjectMapper,
    private val properties: JobHunterProperties,
    private val scraperProperties: ScraperProperties,
) : JobHunterClient {
    private val clientBuilder =
        restClientBuilder
            .clone()
            .baseUrl(properties.apiUrl)
    private val client = newClient()

    override fun claim(source: JobSource): RunClaim? {
        val response = request(HttpMethod.POST, "/scraping/sources/${source.id}/claim", ClaimRequest(scraperProperties.workerId), "claim")
        return if (response.status == 204) null else parse(response.body, "claim")
    }

    override fun heartbeat(claim: RunClaim) {
        request(HttpMethod.POST, "/scraping/runs/${claim.runId}/heartbeat", LeaseRequest(claim.leaseToken), "heartbeat")
    }

    override fun batch(
        claim: RunClaim,
        batchId: UUID,
        jobs: List<ScrapedJob>,
        checkpoint: Map<String, String>,
        fetchedCount: Int,
    ): Int {
        val response =
            request(
                HttpMethod.POST,
                "/scraping/runs/${claim.runId}/batches",
                BatchRequest(claim.leaseToken, batchId, jobs, checkpoint, fetchedCount),
                "batch",
            )
        return parse<BatchResponse>(response.body, "batch").acceptedCount
    }

    override fun complete(claim: RunClaim) {
        request(HttpMethod.POST, "/scraping/runs/${claim.runId}/complete", LeaseRequest(claim.leaseToken), "complete")
    }

    override fun fail(
        claim: RunClaim,
        reason: String,
    ) {
        request(HttpMethod.POST, "/scraping/runs/${claim.runId}/fail", FailRequest(claim.leaseToken, reason), "fail")
    }

    override fun proxies(source: JobSource): List<SourceProxy> {
        val path = if (source == JobSource.LINKEDIN) "/proxies/all" else "/proxies?source=${source.id}"
        val response = request(HttpMethod.GET, path, null, "proxy")
        return if (source == JobSource.LINKEDIN) parse(response.body, "proxy") else listOf(parse(response.body, "proxy"))
    }

    override fun checkJobs(jobs: List<JobCheckRequest>): JobCheckResult {
        val response = request(HttpMethod.POST, "/jobs/check", jobs, "check_jobs")
        return parse(response.body, "check_jobs")
    }

    private inline fun <reified T> parse(
        body: String,
        operation: String,
    ): T =
        try {
            objectMapper.readValue(body)
        } catch (_: Exception) {
            throw ControlPlanePayloadException(operation)
        }

    private fun request(
        method: HttpMethod,
        path: String,
        body: Any?,
        operation: String,
    ): ApiResponse {
        var token = tokenProvider.token()
        var refreshed = false
        while (true) {
            val response = executeWithRetry(method, path, body, token, operation)
            if (response.status != 401 || refreshed || !properties.authEnabled) {
                if (response.status == 409) {
                    when (errorCode(response.body)) {
                        "SCRAPING_LEASE_LOST" -> throw LeaseLostException(response.statusCode, operation)
                        "SCRAPING_SOURCE_DISABLED" -> throw SourceDisabledException(response.statusCode, operation)
                    }
                }
                if (response.status !in 200..299) throw ControlPlaneException(response.statusCode, operation)
                return response
            }
            tokenProvider.invalidate(token)
            token = tokenProvider.token(forceRefresh = true)
            refreshed = true
        }
    }

    private fun errorCode(body: String): String? =
        runCatching {
            objectMapper
                .readTree(body)
                .path("code")
                .asText()
                .takeIf(String::isNotBlank)
        }.getOrNull()

    private fun executeWithRetry(
        method: HttpMethod,
        path: String,
        body: Any?,
        token: String,
        operation: String,
    ): ApiResponse {
        var lastFailure: RuntimeException? = null
        var requestClient = client
        for (attempt in 1..properties.attempts) {
            try {
                val response = exchange(requestClient, method, path, body, token, attempt)
                if (response.status == 429 || response.status in 500..599) {
                    if (attempt == properties.attempts) return response
                    sleep(response.retryAfter ?: backoff(attempt))
                    continue
                }
                return response
            } catch (failure: ResourceAccessException) {
                lastFailure = failure
                if (attempt == properties.attempts) throw ControlPlaneTransportException(operation, failure)
                requestClient = newClient()
                sleep(backoff(attempt))
            }
        }
        throw ControlPlaneTransportException(operation, checkNotNull(lastFailure))
    }

    private fun exchange(
        client: RestClient,
        method: HttpMethod,
        path: String,
        body: Any?,
        token: String,
        attempt: Int,
    ): ApiResponse {
        var request =
            client
                .method(method)
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .headers { headers ->
                    if (token.isNotBlank()) headers.setBearerAuth(token)
                }
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).body(body)
        return request.exchange { _, response ->
            val status = response.statusCode.value()
            val responseBody =
                if ((status in 200..299 && status != 204) || status == 409) {
                    BoundedResponseReader.read(response.body, response.headers, properties.maxResponseBytes)
                } else {
                    ""
                }
            val retryAfter =
                if (status == 429) {
                    HttpRetrySupport.retryDelay(
                        response.headers,
                        attempt,
                        properties.retryDelay,
                        properties.maxRetryDelay,
                    )
                } else {
                    null
                }
            ApiResponse(response.statusCode, responseBody, retryAfter)
        } ?: throw ResourceAccessException("API response was empty")
    }

    private fun newClient(): RestClient =
        clientBuilder
            .clone()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(properties.timeout).build(),
                ).apply { setReadTimeout(properties.timeout) },
            ).build()

    private fun backoff(attempt: Int): Duration =
        HttpRetrySupport.retryDelay(
            HttpHeaders.EMPTY,
            attempt,
            properties.retryDelay,
            properties.maxRetryDelay,
        )

    private fun sleep(delay: Duration) {
        try {
            Thread.sleep(delay.toMillis())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResourceAccessException("API retry interrupted", java.io.IOException(interrupted))
        }
    }

    private data class ClaimRequest(
        val workerId: String,
    )

    private data class LeaseRequest(
        val leaseToken: UUID,
    )

    private data class BatchRequest(
        val leaseToken: UUID,
        val batchId: UUID,
        val jobs: List<ScrapedJob>,
        val checkpoint: Map<String, String>,
        val fetchedCount: Int,
    )

    private data class BatchResponse(
        val acceptedCount: Int,
    )

    private data class FailRequest(
        val leaseToken: UUID,
        val reason: String,
    )

    private data class ApiResponse(
        val statusCode: org.springframework.http.HttpStatusCode,
        val body: String,
        val retryAfter: Duration?,
    ) {
        val status: Int = statusCode.value()
    }
}
