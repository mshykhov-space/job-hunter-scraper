package com.mshykhov.jobhunterscraper.infrastructure.api

import org.springframework.http.HttpStatusCode

class LeaseLostException(
    status: HttpStatusCode,
    operation: String,
) : ControlPlaneException(status, operation)
