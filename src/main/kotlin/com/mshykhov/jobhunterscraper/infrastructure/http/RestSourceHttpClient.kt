package com.mshykhov.jobhunterscraper.infrastructure.http

import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ProxyEndpoint
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import jakarta.annotation.PreDestroy
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class RestSourceHttpClient(
    private val restClientBuilder: RestClient.Builder,
    private val properties: ScraperProperties,
) : SourceHttpClient {
    private val directTransports = ConcurrentHashMap<Duration, Transport>()
    private val proxyTransports =
        object : LinkedHashMap<ProxyKey, Transport>(MAX_PROXY_TRANSPORTS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProxyKey, Transport>): Boolean {
                val remove = size > MAX_PROXY_TRANSPORTS
                if (remove) eldest.value.client.shutdownNow()
                return remove
            }
        }

    override fun get(
        url: String,
        headers: Map<String, String>,
        proxy: ProxyEndpoint?,
    ): String = execute(HttpMethod.GET, url, null, headers, proxy)

    override fun post(
        url: String,
        body: Any,
        headers: Map<String, String>,
        proxy: ProxyEndpoint?,
    ): String = execute(HttpMethod.POST, url, body, headers, proxy)

    private fun execute(
        method: HttpMethod,
        url: String,
        body: Any?,
        headers: Map<String, String>,
        proxy: ProxyEndpoint?,
    ): String {
        val uri = parseUrl(url)
        val target = safeTarget(uri)
        var lastFailure: RuntimeException? = null
        for (attempt in 1..properties.httpAttempts) {
            try {
                return exchange(method, uri, body, headers, proxy, target, attempt)
            } catch (failure: RuntimeException) {
                lastFailure = failure
                val retryAfter = (failure as? OutboundHttpException)?.retryAfter
                if (attempt == properties.httpAttempts || !isRetryable(failure)) {
                    if (failure is ResourceAccessException) throw SourceTransportException(target)
                    throw failure
                }
                sleep(retryAfter ?: backoff(attempt))
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun exchange(
        method: HttpMethod,
        uri: URI,
        body: Any?,
        headers: Map<String, String>,
        proxy: ProxyEndpoint?,
        target: String,
        attempt: Int,
    ): String {
        val client =
            restClientBuilder
                .clone()
                .requestFactory(requestFactory(proxy, timeout(uri), uri))
                .build()
        var request = client.method(method).uri(URI.create(target)).headers { it.setAll(headers) }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body)
        }
        return request.exchange { _, response ->
            if (!response.statusCode.is2xxSuccessful) {
                val delay =
                    if (response.statusCode.value() == 429) {
                        HttpRetrySupport.retryDelay(
                            response.headers,
                            attempt,
                            properties.retryDelay,
                            properties.maxRetryDelay,
                        )
                    } else {
                        null
                    }
                throw OutboundHttpException(response.statusCode, delay, target)
            }
            BoundedResponseReader.read(response.body, response.headers, properties.maxResponseBytes)
        } ?: throw ResourceAccessException("HTTP response was empty")
    }

    private fun requestFactory(
        proxy: ProxyEndpoint?,
        timeout: Duration,
        actualUri: URI,
    ): SecretSafeClientHttpRequestFactory {
        val transport =
            if (proxy == null) {
                directTransports.computeIfAbsent(timeout) { createTransport(null, timeout) }
            } else {
                val key = ProxyKey(proxy.host, proxy.port, proxy.username, proxy.password, timeout)
                synchronized(proxyTransports) { proxyTransports.getOrPut(key) { createTransport(proxy, timeout) } }
            }
        return SecretSafeClientHttpRequestFactory(transport.requestFactory, actualUri)
    }

    private fun createTransport(
        proxy: ProxyEndpoint?,
        timeout: Duration,
    ): Transport {
        val builder = HttpClient.newBuilder().connectTimeout(timeout)
        if (proxy != null) {
            builder.proxy(ProxySelector.of(InetSocketAddress(proxy.host, proxy.port)))
            if (proxy.username != null && proxy.password != null) builder.authenticator(ProxyAuthenticator(proxy))
        }
        val client = builder.build()
        return Transport(client, JdkClientHttpRequestFactory(client).apply { setReadTimeout(timeout) })
    }

    @PreDestroy
    fun close() {
        directTransports.values.forEach { it.client.shutdown() }
        synchronized(proxyTransports) { proxyTransports.values.forEach { it.client.shutdown() } }
    }

    private fun timeout(uri: URI): Duration {
        val target = safeTarget(uri)
        val configuredSource =
            properties.endpoints.entries
                .firstOrNull { (_, endpoint) -> target.startsWith(endpoint.removeSuffix("/")) }
                ?.key
        if (configuredSource != null) {
            return properties.sourceTimeouts[configuredSource] ?: properties.httpTimeout
        }
        val linkedInEndpoint = properties.endpoint(JobSource.LINKEDIN, DEFAULT_JOBSPY_ENDPOINT).removeSuffix("/")
        return if (target.startsWith(linkedInEndpoint)) properties.timeout(JobSource.LINKEDIN) else properties.httpTimeout
    }

    private fun isRetryable(failure: RuntimeException): Boolean =
        failure is ResourceAccessException ||
            failure is OutboundHttpException &&
            (failure.status.value() == 429 || failure.status.is5xxServerError)

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
            throw ResourceAccessException("HTTP retry interrupted", java.io.IOException(interrupted))
        }
    }

    private fun parseUrl(url: String): URI =
        try {
            URI.create(url)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid HTTP URL")
        }

    private fun safeTarget(uri: URI): String = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()

    private class ProxyAuthenticator(
        private val proxy: ProxyEndpoint,
    ) : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? =
            if (
                requestorType == RequestorType.PROXY &&
                requestingHost == proxy.host &&
                requestingPort == proxy.port
            ) {
                PasswordAuthentication(proxy.username, proxy.password?.toCharArray())
            } else {
                null
            }
    }

    private data class ProxyKey(
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
        val timeout: Duration,
    )

    private data class Transport(
        val client: HttpClient,
        val requestFactory: JdkClientHttpRequestFactory,
    )

    private companion object {
        const val DEFAULT_JOBSPY_ENDPOINT = "http://127.0.0.1:8000"
        const val MAX_PROXY_TRANSPORTS = 64
    }
}
