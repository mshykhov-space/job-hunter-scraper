package com.mshykhov.jobhunterscraper.application.model

class SourceProxy(
    val url: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val countryCode: String? = null,
    val fingerprint: Map<String, String> = emptyMap(),
) {
    fun endpoint(): ProxyEndpoint = ProxyEndpoint(host, port, username, password)
}
