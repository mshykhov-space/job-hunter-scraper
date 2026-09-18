package com.mshykhov.jobhunterscraper.infrastructure.http

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import java.io.IOException
import java.io.OutputStream
import java.net.URI

internal class SecretSafeClientHttpRequestFactory(
    private val delegate: ClientHttpRequestFactory,
    private val actualUri: URI,
) : ClientHttpRequestFactory {
    override fun createRequest(
        uri: URI,
        httpMethod: HttpMethod,
    ): ClientHttpRequest = SecretSafeClientHttpRequest(delegate.createRequest(actualUri, httpMethod), uri)

    private class SecretSafeClientHttpRequest(
        private val delegate: ClientHttpRequest,
        private val sanitizedUri: URI,
    ) : ClientHttpRequest {
        override fun getMethod(): HttpMethod = delegate.method

        override fun getURI(): URI = sanitizedUri

        override fun getAttributes(): MutableMap<String, Any> = delegate.attributes

        override fun getHeaders(): HttpHeaders = delegate.headers

        override fun getBody(): OutputStream = delegate.body

        override fun execute(): ClientHttpResponse =
            try {
                delegate.execute()
            } catch (_: IOException) {
                throw IOException("HTTP request failed for $sanitizedUri")
            }
    }
}
