package com.mshykhov.jobhunterscraper.infrastructure.http

import com.mshykhov.jobhunterscraper.application.model.ProxyEndpoint

interface SourceHttpClient {
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxy: ProxyEndpoint? = null,
    ): String

    fun post(
        url: String,
        body: Any,
        headers: Map<String, String> = emptyMap(),
        proxy: ProxyEndpoint? = null,
    ): String
}
