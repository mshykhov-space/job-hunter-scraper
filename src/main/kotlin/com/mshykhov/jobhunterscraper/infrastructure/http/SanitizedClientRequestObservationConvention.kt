package com.mshykhov.jobhunterscraper.infrastructure.http

import io.micrometer.common.KeyValue
import org.springframework.http.client.observation.ClientRequestObservationContext
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention
import java.net.URI

class SanitizedClientRequestObservationConvention : DefaultClientRequestObservationConvention() {
    override fun requestUri(context: ClientRequestObservationContext): KeyValue {
        val key = super.requestUri(context).key
        val requestUri = context.carrier?.uri ?: return super.requestUri(context)
        val sanitized =
            runCatching {
                URI(requestUri.scheme, null, requestUri.host, requestUri.port, requestUri.path, null, null).toASCIIString()
            }.getOrElse { "${requestUri.scheme}://${requestUri.host ?: "unknown"}" }
        return KeyValue.of(key, sanitized)
    }
}
