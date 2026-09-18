package com.mshykhov.jobhunterscraper.infrastructure.http

import org.springframework.http.HttpStatusCode
import java.time.Duration

class OutboundHttpException(
    val status: HttpStatusCode,
    val retryAfter: Duration? = null,
    target: String,
) : RuntimeException("HTTP ${status.value()} from $target")
