package com.mshykhov.jobhunterscraper.application.model

class ProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)
