package com.mshykhov.jobhunterscraper.infrastructure.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.infrastructure.config.JobHunterProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.BoundedResponseReader
import com.mshykhov.jobhunterscraper.infrastructure.http.HttpRetrySupport
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class AuthentikTokenProvider(
    restClientBuilder: RestClient.Builder,
    private val objectMapper: ObjectMapper,
    private val properties: JobHunterProperties,
    private val clock: Clock,
) {
    private val monitor = Any()
    private val client =
        restClientBuilder
            .clone()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(properties.timeout).build(),
                ).apply { setReadTimeout(properties.timeout) },
            ).build()

    @Volatile
    private var cached: CachedToken? = null

    fun token(forceRefresh: Boolean = false): String {
        if (!properties.authEnabled) return ""
        val current = cached
        if (!forceRefresh && current?.isUsable(clock.instant()) == true) return current.value
        return synchronized(monitor) {
            val locked = cached
            if (!forceRefresh && locked?.isUsable(clock.instant()) == true) locked.value else fetch().also { cached = it }.value
        }
    }

    fun invalidate(value: String) {
        synchronized(monitor) {
            if (cached?.value == value) cached = null
        }
    }

    private fun fetch(): CachedToken {
        check(properties.clientId.isNotBlank() && properties.username.isNotBlank() && properties.password.isNotBlank()) {
            "Job Hunter authentication is enabled but credentials are incomplete"
        }
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "client_credentials")
                add("client_id", properties.clientId)
                add("username", properties.username)
                add("password", properties.password)
                add("scope", properties.scope)
            }
        val response = requestToken(form)
        val token =
            runCatching { objectMapper.readValue(response, TokenResponse::class.java) }
                .getOrElse { throw IllegalStateException("Authentication response is invalid") }
        check(token.accessToken.isNotBlank() && token.expiresIn > 0) { "Authentication response is invalid" }
        val skewSeconds = minOf(60L, maxOf(1L, token.expiresIn / 10))
        return CachedToken(token.accessToken, clock.instant().plusSeconds(token.expiresIn - skewSeconds))
    }

    private fun requestToken(form: LinkedMultiValueMap<String, String>): String {
        for (attempt in 1..properties.attempts) {
            try {
                return client
                    .post()
                    .uri(properties.tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange { _, httpResponse ->
                        if (!httpResponse.statusCode.is2xxSuccessful) {
                            val retryAfter =
                                if (httpResponse.statusCode.value() == 429) {
                                    HttpRetrySupport.retryDelay(
                                        httpResponse.headers,
                                        attempt,
                                        properties.retryDelay,
                                        properties.maxRetryDelay,
                                        clock,
                                    )
                                } else {
                                    null
                                }
                            throw TokenRequestException(httpResponse.statusCode, retryAfter)
                        }
                        BoundedResponseReader.read(httpResponse.body, httpResponse.headers, properties.maxResponseBytes)
                    } ?: throw ResourceAccessException("Authentication response was empty")
            } catch (failure: RuntimeException) {
                val retryable =
                    failure is ResourceAccessException ||
                        failure is TokenRequestException &&
                        (failure.status.value() == 429 || failure.status.is5xxServerError)
                if (!retryable || attempt == properties.attempts) {
                    if (failure is ResourceAccessException) throw IllegalStateException("Authentication transport failed")
                    throw failure
                }
                sleep((failure as? TokenRequestException)?.retryAfter ?: backoff(attempt))
            }
        }
        throw IllegalStateException("Authentication transport failed")
    }

    private fun backoff(attempt: Int): Duration =
        HttpRetrySupport.retryDelay(
            HttpHeaders.EMPTY,
            attempt,
            properties.retryDelay,
            properties.maxRetryDelay,
            clock,
        )

    private fun sleep(delay: Duration) {
        try {
            Thread.sleep(delay.toMillis())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Authentication retry interrupted")
        }
    }

    private data class CachedToken(
        val value: String,
        val usableUntil: Instant,
    ) {
        fun isUsable(now: Instant): Boolean = now.isBefore(usableUntil)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("expires_in") val expiresIn: Long,
    )

    private class TokenRequestException(
        val status: HttpStatusCode,
        val retryAfter: Duration?,
    ) : RuntimeException("Authentication endpoint returned HTTP ${status.value()}")
}
