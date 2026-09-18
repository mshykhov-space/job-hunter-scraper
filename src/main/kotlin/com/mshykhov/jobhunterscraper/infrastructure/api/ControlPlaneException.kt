package com.mshykhov.jobhunterscraper.infrastructure.api

import org.springframework.http.HttpStatusCode

open class ControlPlaneException(
    val status: HttpStatusCode,
    operation: String,
) : RuntimeException("Job Hunter API returned HTTP ${status.value()} for $operation")
